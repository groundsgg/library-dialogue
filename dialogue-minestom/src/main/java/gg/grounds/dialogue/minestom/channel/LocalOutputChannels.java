package gg.grounds.dialogue.minestom.channel;

import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

/**
 * Local registered layers, with serialized asynchronous writes and no foreign calls under locks.
 */
public final class LocalOutputChannels implements OutputChannels {
  private final Object lock = new Object();
  private final IdentityHashMap<Player, PlayerState> players = new IdentityHashMap<>();
  private final BiFunction<Player, Component, CompletionStage<Void>> hudSink;
  private final BiFunction<Player, List<Key>, CompletionStage<Void>> effectSink;
  private final boolean effectsSupported;
  private long sequence;
  private final ReferenceQueue<Player> expiredPlayers = new ReferenceQueue<>();
  private final Map<PlayerKey, Long> generations = new HashMap<>();

  private static final class PlayerKey extends WeakReference<Player> {
    final int hash;

    PlayerKey(Player player, ReferenceQueue<Player> queue) {
      super(player, queue);
      hash = System.identityHashCode(player);
    }

    public int hashCode() {
      return hash;
    }

    public boolean equals(Object other) {
      return this == other
          || (other instanceof PlayerKey key && get() != null && get() == key.get());
    }
  }

  public LocalOutputChannels(
      BiFunction<Player, Component, CompletionStage<Void>> hudSink,
      boolean effectsSupported,
      BiFunction<Player, List<Key>, CompletionStage<Void>> effectSink) {
    this.hudSink = Objects.requireNonNull(hudSink);
    this.effectSink = Objects.requireNonNull(effectSink);
    this.effectsSupported = effectsSupported;
  }

  public boolean supportedEffects() {
    return effectsSupported;
  }

  private final class PlayerState {
    final Player player;
    final Map<Long, Channel<Component>> hud = new HashMap<>();
    final Map<Long, Channel<List<Key>>> effects = new HashMap<>();
    volatile long latest;
    volatile boolean retired;
    int sources, pending, inflight;

    PlayerState(Player p, long g) {
      player = p;
      latest = g;
    }
  }

  private PlayerState state(Player player, long generation) {
    if (generation < 0) throw new IllegalArgumentException("generation");
    Reference<? extends Player> expired;
    while ((expired = expiredPlayers.poll()) != null) generations.remove(expired);
    var key = new PlayerKey(player, expiredPlayers);
    var known = generations.get(key);
    if (known != null && generation < known) throw new IllegalArgumentException("STALE_CONNECTION");
    generations.put(key, generation);
    var s = players.get(player);
    if (s == null) {
      s = new PlayerState(player, generation);
      players.put(player, s);
    }
    if (generation < s.latest) throw new IllegalArgumentException("STALE_CONNECTION");
    s.latest = generation;
    return s;
  }

  public HudLease acquireHud(Player player, long generation, int priority) {
    Objects.requireNonNull(player);
    synchronized (lock) {
      var s = state(player, generation);
      var channel = s.hud.computeIfAbsent(generation, g -> new Channel<>(s, g, true, hudSink));
      var source = new HudSource(channel, priority);
      channel.sources.put(source.id, source);
      s.sources++;
      return source;
    }
  }

  public EffectLease acquireEffects(Player player, long generation) {
    if (!effectsSupported) throw new UnsupportedOperationException("POST_EFFECTS_UNAVAILABLE");
    Objects.requireNonNull(player);
    synchronized (lock) {
      var s = state(player, generation);
      var channel =
          s.effects.computeIfAbsent(generation, g -> new Channel<>(s, g, false, effectSink));
      var source = new EffectSource(channel);
      channel.sources.put(source.id, source);
      s.sources++;
      return source;
    }
  }

  private void retire(PlayerState state) {
    if (state.sources == 0 && state.pending == 0 && state.inflight == 0) {
      state.retired = true;
      players.remove(state.player, state);
    }
  }

  private final class HudSource extends Source<Component> implements HudLease {
    HudSource(Channel<Component> c, int priority) {
      super(c, priority);
    }

    public boolean visible() {
      if (closed || !channel.current()) return false;
      synchronized (lock) {
        return channel.sources.values().stream()
                .filter(s -> s.value != null && !s.closed)
                .max(
                    Comparator.<Source<Component>>comparingInt(s -> s.priority)
                        .thenComparingLong(s -> s.id))
                .orElse(null)
            == this;
      }
    }
  }

  private final class EffectSource extends Source<List<Key>> implements EffectLease {
    EffectSource(Channel<List<Key>> c) {
      super(c, 0);
    }

    public CompletionStage<Void> replace(List<Key> values) {
      return super.replace(List.copyOf(values));
    }
  }

  private class Source<T> implements Lease<T> {
    final Channel<T> channel;
    final long id = ++sequence;
    final int priority;
    T value;
    volatile boolean closed;
    final CompletableFuture<Void> closeResult = new CompletableFuture<>();
    final CompletionStage<Void> closeView = closeResult.minimalCompletionStage();

    Source(Channel<T> c, int priority) {
      channel = c;
      this.priority = priority;
    }

    public CompletionStage<Void> replace(T value) {
      Objects.requireNonNull(value);
      var result = new CompletableFuture<Void>();
      synchronized (lock) {
        if (closed || channel.state.latest != channel.generation || channel.state.retired) {
          result.completeExceptionally(new CancellationException("Layer closed"));
          return result.minimalCompletionStage();
        }
        if (channel.queue.size() >= 128) {
          result.completeExceptionally(new IllegalStateException("OUTPUT_QUEUE_FULL"));
          return result.minimalCompletionStage();
        }
        this.value = value;
        channel.enqueue(new Operation<>(this, false, result));
      }
      channel.pump();
      return result.minimalCompletionStage();
    }

    public CompletionStage<Void> closeAsync() {
      synchronized (lock) {
        if (closed) return closeView;
        closed = true;
        channel.sources.remove(id);
        channel.state.sources--;
        channel.enqueue(new Operation<>(this, true, closeResult));
      }
      channel.pump();
      return closeView;
    }
  }

  private final class Operation<T> {
    final Source<T> source;
    final boolean closing;
    final CompletableFuture<Void> result;

    Operation(Source<T> source, boolean closing, CompletableFuture<Void> result) {
      this.source = source;
      this.closing = closing;
      this.result = result;
    }
  }

  private final class Channel<T> {
    final PlayerState state;
    final long generation;
    final Instance instance;
    final boolean isHud;
    final BiFunction<Player, T, CompletionStage<Void>> sink;
    final LinkedHashMap<Long, Source<T>> sources = new LinkedHashMap<>();
    final ArrayDeque<Operation<T>> queue = new ArrayDeque<>();
    final AtomicInteger work = new AtomicInteger();
    boolean writing;

    Channel(
        PlayerState state,
        long generation,
        boolean hud,
        BiFunction<Player, T, CompletionStage<Void>> sink) {
      this.state = state;
      this.generation = generation;
      instance = state.player.getInstance();
      isHud = hud;
      this.sink = sink;
    }

    void enqueue(Operation<T> op) {
      queue.add(op);
      state.pending++;
    }

    boolean current() {
      return !state.retired
          && state.latest == generation
          && state.player.isOnline()
          && state.player.getInstance() == instance;
    }

    @SuppressWarnings("unchecked")
    T payload() {
      if (isHud)
        return sources.values().stream()
            .filter(s -> s.value != null && !s.closed)
            .max(Comparator.<Source<T>>comparingInt(s -> s.priority).thenComparingLong(s -> s.id))
            .map(s -> s.value)
            .orElse((T) Component.empty());
      var keys = new LinkedHashSet<Key>();
      for (var source : sources.values())
        if (!source.closed && source.value != null) keys.addAll((List<Key>) source.value);
      return (T) List.copyOf(keys);
    }

    void pump() {
      if (work.getAndIncrement() != 0) return;
      int missed = 1;
      do {
        Operation<T> op = null;
        T value = null;
        synchronized (lock) {
          if (!writing && !queue.isEmpty()) {
            op = queue.remove();
            state.pending--;
            writing = true;
            state.inflight++;
            value = payload();
          }
        }
        if (op != null) {
          var operation = op;
          if (!current() || (!operation.closing && operation.source.closed)) {
            finish(operation, operation.closing ? null : new CancellationException("Stale output"));
          } else {
            CompletionStage<Void> stage;
            try {
              stage = Objects.requireNonNull(sink.apply(state.player, value));
            } catch (Throwable error) {
              stage = CompletableFuture.failedFuture(error);
            }
            stage.whenComplete((v, error) -> finish(operation, error));
          }
        }
        missed = work.addAndGet(-missed);
      } while (missed != 0);
    }

    void finish(Operation<T> op, Throwable error) {
      synchronized (lock) {
        writing = false;
        state.inflight--;
        retire(state);
      }
      if (error == null) op.result.complete(null);
      else op.result.completeExceptionally(error);
      pump();
    }
  }
}
