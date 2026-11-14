package gg.grounds.dialogue.minestom;

import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.validation.ContentFingerprint;
import gg.grounds.dialogue.minestom.internal.*;
import gg.grounds.dialogue.minestom.render.PresentationStatus;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.*;
import net.minestom.server.event.player.*;

/** Owns local dialogue lifetimes; identity/language authority is supplied by the application. */
public final class MinestomDialogueHost {
  @FunctionalInterface
  interface RendererFactory {
    DialogueRenderer create(
        MinestomDialogueRequest request,
        ConnectionGuard guard,
        Consumer<PresentationStatus> status);
  }

  private final Object lock = new Object();
  private final MinestomDialogueOptions options;
  private final RendererFactory renderers;
  private final Map<String, Ticket> starts = new HashMap<>();
  private final IdentityHashMap<Player, Ticket> active = new IdentityHashMap<>();
  private final List<MinestomDialogueSession> failedOwners = new ArrayList<>();
  private final EventNode<Event> events;
  private boolean closing;
  private CompletableFuture<Void> closed;
  private CompletionStage<Void> closeView;

  // Default surface wiring is added with the real renderer coordinator; no placeholder surfaces.
  MinestomDialogueHost(MinestomDialogueOptions options, RendererFactory renderers) {
    this.options = Objects.requireNonNull(options);
    this.renderers = Objects.requireNonNull(renderers);
    events = EventNode.all("dialogue-" + UUID.randomUUID());
    events.addListener(
        PlayerDisconnectEvent.class,
        e -> {
          var ticket = current(e.getPlayer());
          if (ticket != null) ticket.cancel(DialogueEndReason.DISCONNECTED);
        });
    events.addListener(
        PlayerTickEvent.class,
        e -> {
          var ticket = current(e.getPlayer());
          if (ticket != null) {
            var session = ticket.session;
            if (session != null) session.checkConnection();
          }
        });
    events.addListener(
        PlayerSpawnEvent.class,
        e -> {
          var ticket = current(e.getPlayer());
          if (ticket != null) {
            var session = ticket.session;
            if (session != null) session.checkConnection();
          }
        });
    MinecraftServer.getGlobalEventHandler().addChild(events);
  }

  private Ticket current(Player player) {
    synchronized (lock) {
      return active.get(player);
    }
  }

  public MinestomDialogueStart begin(MinestomDialogueRequest request) {
    Objects.requireNonNull(request);
    String identity = identity(request);
    var admissionInstance = request.player().getInstance();
    Ticket ticket, previous;
    String reject = null;
    synchronized (lock) {
      var old = starts.get(request.requestId());
      if (old != null) {
        if (old.identity.equals(identity) && old.player.get() == request.player()) return old;
        return rejected(request, "START_REQUEST_CONFLICT");
      }
      if (closing) reject = "HOST_CLOSED";
      else if (starts.size() >= 65536) reject = "START_REGISTER_FULL";
      if (reject != null) return rejected(request, reject);
      ticket = new Ticket(request, identity, this::finished);
      starts.put(request.requestId(), ticket);
      previous = admissionInstance == null ? null : active.put(request.player(), ticket);
    }
    if (admissionInstance == null) {
      ticket.reject("HOST_CHANGED", null);
      return ticket;
    }
    var before =
        previous == null
            ? CompletableFuture.<Void>completedFuture(null)
            : previous.cancel(DialogueEndReason.SUPERSEDED);
    before.whenComplete(
        (v, error) -> {
          if (error != null) {
            ticket.reject("CLEANUP_FAILED", error);
            return;
          }
          if (ticket.cancelled()) return;
          var guard =
              new ConnectionGuard(request.player(), request.hostGeneration(), admissionInstance);
          if (!guard.isCurrent()) {
            ticket.reject("HOST_CHANGED", null);
            return;
          }
          try {
            var renderer = Objects.requireNonNull(renderers.create(request, guard, ticket::status));
            var session =
                new MinestomDialogueSession(
                    request,
                    options,
                    guard,
                    renderer,
                    ticket.token,
                    ticket::ready,
                    ticket::settled);
            if (ticket.attach(session)) session.begin();
          } catch (Throwable failure) {
            ticket.reject("START_FAILED", failure);
          }
        });
    return ticket;
  }

  private void finished(Ticket ticket, MinestomDialogueSession failed) {
    synchronized (lock) {
      Player player = ticket.player.get();
      if (player != null) active.remove(player, ticket);
      if (failed != null) failedOwners.add(failed);
    }
  }

  private static Ticket rejected(MinestomDialogueRequest request, String code) {
    var ticket = new Ticket(request, "", (t, f) -> {});
    ticket.reject(code, null);
    return ticket;
  }

  public CompletionStage<Void> closeAsync() {
    List<Ticket> tickets;
    synchronized (lock) {
      if (closed != null) return closeView;
      closing = true;
      closed = new CompletableFuture<>();
      closeView = closed.minimalCompletionStage();
      tickets = List.copyOf(starts.values());
    }
    MinecraftServer.getGlobalEventHandler().removeChild(events);
    var pending =
        tickets.stream()
            .map(t -> t.cancel(DialogueEndReason.HOST_CLOSED).toCompletableFuture())
            .toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(pending)
        .whenComplete(
            (v, error) -> {
              synchronized (lock) {
                starts.clear();
                active.clear();
              }
              if (error == null) closed.complete(null);
              else closed.completeExceptionally(error);
            });
    return closeView;
  }

  private static String identity(MinestomDialogueRequest q) {
    var bindings = new TreeMap<String, Object>();
    q.speakers()
        .forEach(
            (id, b) ->
                bindings.put(
                    id,
                    Map.of(
                        "generation",
                        b.generation(),
                        "instance",
                        b.instance().getUuid().toString())));
    return ContentFingerprint.hash(
            ContentFingerprint.canonicalBytes(
                Map.ofEntries(
                    Map.entry("player", q.player().getUuid().toString()),
                    Map.entry("hostGeneration", q.hostGeneration()),
                    Map.entry("content", q.content().contentPin().sha256()),
                    Map.entry("author", q.content().catalog().generation()),
                    Map.entry("policy", q.content().limits().toString()),
                    Map.entry("services", q.services().generation()),
                    Map.entry("speakers", bindings),
                    Map.entry("actors", q.actors() != null),
                    Map.entry(
                        "capabilities", q.services().capabilities().stream().sorted().toList()),
                    Map.entry(
                        "locale",
                        List.of(q.locale().tag(), q.locale().revision(), q.locale().fallbackTag())),
                    Map.entry(
                        "preferences",
                        Map.of(
                            "surface",
                            q.preferences().surfaceKey(),
                            "delay",
                            q.preferences().graphemeDelay().toNanos(),
                            "reducedMotion",
                            q.preferences().reducedMotion(),
                            "effects",
                            q.preferences().effects())),
                    Map.entry("context", ContentFingerprint.argumentData(q.context())),
                    Map.entry(
                        "controls",
                        Map.of(
                            "open",
                            q.controls().open().stream().map(Enum::name).sorted().toList(),
                            "confirm",
                            q.controls().confirm().stream().map(Enum::name).sorted().toList(),
                            "primary",
                            q.controls().primaryConfirm().name(),
                            "scroll",
                            q.controls().scroll(),
                            "quiet",
                            q.controls().quietPeriod().toNanos())),
                    Map.entry("openingImpulse", q.openingImpulse()))))
        .sha256();
  }

  private static final class Ticket implements MinestomDialogueStart {
    final Object lock = new Object();
    final String identity;
    final WeakReference<Player> player;
    final CancellationToken token = new CancellationToken();
    final java.util.concurrent.atomic.AtomicReference<DialogueEndReason> cancelReason =
        new java.util.concurrent.atomic.AtomicReference<>();
    final CompletableFuture<DialogueStartResult> result = new CompletableFuture<>();
    final CompletionStage<DialogueStartResult> observation = result.minimalCompletionStage();
    final CompletableFuture<Void> close = new CompletableFuture<>();
    final CompletionStage<Void> closeObservation = close.minimalCompletionStage();
    volatile PresentationStatus status;
    volatile MinestomDialogueSession session;
    BiConsumer<Ticket, MinestomDialogueSession> finished;
    boolean settled;

    Ticket(
        MinestomDialogueRequest q,
        String identity,
        BiConsumer<Ticket, MinestomDialogueSession> finished) {
      this.identity = identity;
      player = new WeakReference<>(q.player());
      status = new PresentationStatus(q.preferences().surfaceKey(), "", "PREPARING", 0);
      this.finished = finished;
    }

    boolean cancelled() {
      return cancelReason.get() != null || token.isCancelled() || close.isDone();
    }

    boolean attach(MinestomDialogueSession value) {
      synchronized (lock) {
        if (cancelled()) return false;
        session = value;
        return true;
      }
    }

    public CompletionStage<DialogueStartResult> result() {
      return observation;
    }

    public PresentationStatus status() {
      return status;
    }

    void status(PresentationStatus value) {
      Objects.requireNonNull(value);
      synchronized (lock) {
        if (!settled && value.surfaceGeneration() >= status.surfaceGeneration()) status = value;
      }
    }

    void ready(DialogueStartResult value) {
      result.complete(value);
    }

    public CompletionStage<Void> cancel(DialogueEndReason reason) {
      Objects.requireNonNull(reason);
      MinestomDialogueSession current;
      boolean noSession;
      synchronized (lock) {
        cancelReason.compareAndSet(null, reason);
        current = session;
        noSession = current == null;
      }
      reason = cancelReason.get();
      try {
        token.cancel(reason);
      } catch (Throwable failure) {
        if (current == null) {
          reject("CANCELLATION_FAILED", failure);
          return closeObservation;
        }
      }
      if (current != null) current.cancel(reason);
      else if (noSession && !close.isDone()) reject(token.reason().name(), null);
      return closeObservation;
    }

    void reject(String code, Throwable error) {
      result.complete(new DialogueStartResult.Rejected(code, List.of()));
      settled(null, error);
    }

    void settled(MinestomDialogueSession source, Throwable error) {
      BiConsumer<Ticket, MinestomDialogueSession> callback;
      synchronized (lock) {
        if (settled) return;
        settled = true;
        session = null;
        callback = finished;
        finished = null;
      }
      if (!result.isDone())
        result.complete(
            new DialogueStartResult.Rejected(
                error != null
                    ? "CLEANUP_FAILED"
                    : token.isCancelled() ? token.reason().name() : "START_FAILED",
                List.of()));
      if (callback != null) callback.accept(this, error == null ? null : source);
      status =
          new PresentationStatus(
              status.requestedSurface(),
              "",
              error == null ? "CLOSED" : "CLEANUP_FAILED",
              status.surfaceGeneration());
      if (error == null) close.complete(null);
      else close.completeExceptionally(error);
    }
  }
}
