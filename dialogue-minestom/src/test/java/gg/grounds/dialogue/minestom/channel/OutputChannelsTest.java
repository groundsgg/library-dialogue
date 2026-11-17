package gg.grounds.dialogue.minestom.channel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class OutputChannelsTest {
  @Test
  void retiredLayersStillRejectAnOlderConnectionGeneration(Env env) {
    var player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var channels =
        new LocalOutputChannels(
            (p, c) -> CompletableFuture.completedFuture(null),
            false,
            (p, e) -> CompletableFuture.completedFuture(null));
    var lease = channels.acquireHud(player, 2, 50);
    lease.replace(Component.text("new"));
    lease.closeAsync().toCompletableFuture().join();
    assertThrows(IllegalArgumentException.class, () -> channels.acquireHud(player, 1, 50));
  }

  @Test
  void higherPriorityLayerReportsSuppressedDialog(Env env) {
    var player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var channels =
        new LocalOutputChannels(
            (p, c) -> CompletableFuture.completedFuture(null),
            false,
            (p, e) -> CompletableFuture.completedFuture(null));
    var dialogue = channels.acquireHud(player, 1, 50);
    dialogue.replace(Component.text("dialogue"));
    assertTrue(dialogue.visible());
    var urgent = channels.acquireHud(player, 1, 100);
    urgent.replace(Component.text("urgent"));
    assertFalse(dialogue.visible());
    urgent.closeAsync().toCompletableFuture().join();
    assertTrue(dialogue.visible());
    dialogue.closeAsync();
  }

  @Test
  void closingOneLeaseRestoresRemainingSource(Env env) {
    var player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var seen = new ArrayList<String>();
    var channels =
        new LocalOutputChannels(
            (p, c) -> {
              seen.add(PlainTextComponentSerializer.plainText().serialize(c));
              return CompletableFuture.completedFuture(null);
            },
            false,
            (p, e) -> CompletableFuture.completedFuture(null));
    var foreign = channels.acquireHud(player, 1, 10);
    foreign.replace(Component.text("foreign")).toCompletableFuture().join();
    var dialogue = channels.acquireHud(player, 1, 50);
    dialogue.replace(Component.text("dialogue")).toCompletableFuture().join();
    dialogue.closeAsync().toCompletableFuture().join();
    assertEquals(List.of("foreign", "dialogue", "foreign"), seen);
    foreign.closeAsync().toCompletableFuture().join();
  }

  @Test
  void oldConnectionGenerationCannotWriteToCurrentHud(Env env) {
    var player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var seen = new ArrayList<String>();
    var channels =
        new LocalOutputChannels(
            (p, c) -> {
              seen.add(PlainTextComponentSerializer.plainText().serialize(c));
              return CompletableFuture.completedFuture(null);
            },
            false,
            (p, e) -> CompletableFuture.completedFuture(null));
    var old = channels.acquireHud(player, 1, 50);
    old.replace(Component.text("old"));
    var fresh = channels.acquireHud(player, 2, 50);
    fresh.replace(Component.text("new")).toCompletableFuture().join();
    old.closeAsync().toCompletableFuture().join();
    assertEquals("new", seen.getLast());
    assertTrue(
        old.replace(Component.text("stale")).toCompletableFuture().isCompletedExceptionally());
    fresh.closeAsync();
  }

  @Test
  void cleanupWaitsForPendingWrites(Env env) {
    var player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var gate = new CompletableFuture<Void>();
    var count = new AtomicInteger();
    var channels =
        new LocalOutputChannels(
            (p, c) -> count.getAndIncrement() == 0 ? gate : CompletableFuture.completedFuture(null),
            false,
            (p, e) -> CompletableFuture.completedFuture(null));
    var lease = channels.acquireHud(player, 1, 50);
    var write = lease.replace(Component.text("visible"));
    var close = lease.closeAsync();
    assertFalse(close.toCompletableFuture().isDone());
    gate.complete(null);
    write.toCompletableFuture().join();
    close.toCompletableFuture().join();
    assertEquals(2, count.get());
    assertSame(close, lease.closeAsync());
  }

  @Test
  void reentrantSinkDoesNotHoldInternalMonitor(Env env) throws Exception {
    var player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var ref = new AtomicReference<OutputChannels.HudLease>();
    var first = new AtomicBoolean(true);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var channels =
          new LocalOutputChannels(
              (p, c) -> {
                if (first.getAndSet(false))
                  try {
                    CompletableFuture.runAsync(() -> ref.get().closeAsync(), executor)
                        .get(1, TimeUnit.SECONDS);
                  } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                  }
                return CompletableFuture.completedFuture(null);
              },
              false,
              (p, e) -> CompletableFuture.completedFuture(null));
      ref.set(channels.acquireHud(player, 1, 50));
      ref.get().replace(Component.text("dialogue")).toCompletableFuture().join();
      ref.get().closeAsync().toCompletableFuture().join();
    }
  }

  @Test
  void removingOneEffectKeepsOtherRegisteredEffects(Env env) {
    var player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var seen = new ArrayList<List<Key>>();
    var channels =
        new LocalOutputChannels(
            (p, c) -> CompletableFuture.completedFuture(null),
            true,
            (p, e) -> {
              seen.add(e);
              return CompletableFuture.completedFuture(null);
            });
    var other = channels.acquireEffects(player, 1);
    other.replace(List.of(Key.key("example:other")));
    var dialogue = channels.acquireEffects(player, 1);
    dialogue.replace(List.of(Key.key("dialogue:vignette")));
    dialogue.closeAsync().toCompletableFuture().join();
    assertEquals(List.of(Key.key("example:other")), seen.getLast());
    other.closeAsync();
  }
}
