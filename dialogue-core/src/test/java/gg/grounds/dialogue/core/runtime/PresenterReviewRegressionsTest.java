package gg.grounds.dialogue.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.presentation.*;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class PresenterReviewRegressionsTest {
  static class Surface implements DialogueSurface {
    int opens, closes, writes;
    final List<SurfaceContext> contexts = new ArrayList<>();
    final List<WidgetFrame> frames = new ArrayList<>();
    CompletableFuture<SurfaceHandle> openGate;
    CompletableFuture<Void> firstWrite;
    CompletableFuture<Void> closeGate;
    boolean failClose;
    WeakReference<SurfaceHandle> lastHandle;

    public CompletionStage<SurfaceHandle> open(SurfaceContext context) {
      opens++;
      contexts.add(context);
      if (openGate != null) return openGate;
      return CompletableFuture.completedFuture(handle());
    }

    SurfaceHandle handle() {
      SurfaceHandle h =
          new SurfaceHandle() {
            public CompletionStage<Void> replace(WidgetFrame frame) {
              writes++;
              frames.add(frame);
              return firstWrite != null && writes == 1
                  ? firstWrite
                  : CompletableFuture.completedFuture(null);
            }

            public CompletionStage<Void> closeAsync() {
              closes++;
              if (closeGate != null) return closeGate;
              return failClose
                  ? CompletableFuture.failedFuture(
                      new IllegalStateException("surface cleanup failed"))
                  : CompletableFuture.completedFuture(null);
            }
          };
      lastHandle = new WeakReference<>(h);
      return h;
    }
  }

  static final class Fixture {
    final TestRig rig = new TestRig();
    final Surface hud = new Surface(), npc = new Surface();
    final AtomicReference<DialogueRenderer.PresentationHandle> presentation =
        new AtomicReference<>();
    final DialoguePresenter presenter;

    Fixture() {
      presenter =
          new DialoguePresenter(
              Map.of(
                  "hud",
                  new DialoguePresenter.SurfaceProfile(
                      hud,
                      LayoutProfile.hud(),
                      DialogueTheme.neutral(),
                      FontMetrics.defaults(),
                      false),
                  "npc",
                  new DialoguePresenter.SurfaceProfile(
                      npc,
                      LayoutProfile.npc(),
                      DialogueTheme.neutral(),
                      FontMetrics.defaults(),
                      false)),
              rig.scheduler);
      rig.bindings =
          new HostBindings(
              "presenter",
              Map.of(),
              Map.of(),
              null,
              rig.bindings.texts(),
              request ->
                  presenter
                      .prepare(request)
                      .thenApply(
                          handle -> {
                            presentation.set(handle);
                            return handle;
                          }),
              null,
              Set.of());
    }

    CompletionStage<Void> change(DialogueHandle h, String key) {
      var f =
          h.updatePreferences(
              new PresentationPreferences(key, Duration.ofMillis(35), false, Map.of()));
      rig.scheduler.drain();
      return f;
    }
  }

  @Test
  void i1LateFailedSurfaceCleanupFailsStartAndRuntimeClose() {
    var f = new Fixture();
    f.hud.openGate = new CompletableFuture<>();
    f.hud.failClose = true;
    var start = f.rig.runtime.start(f.rig.request("late-failure"));
    f.rig.scheduler.drain();
    f.rig.cancellation.cancel(DialogueEndReason.DISCONNECTED);
    f.rig.scheduler.drain();
    f.hud.openGate.complete(f.hud.handle());
    f.rig.scheduler.drain();
    assertEquals("CLEANUP_FAILED", ((DialogueStartResult.Rejected) TestRig.done(start)).code());
    var closed = f.rig.runtime.closeAsync();
    f.rig.scheduler.drain();
    assertTrue(closed.toCompletableFuture().isCompletedExceptionally());
    assertEquals(1, f.hud.closes);
  }

  @Test
  void i4ReturningToCommittedSurfaceSupersedesPendingOpen() {
    var f = new Fixture();
    var h = f.rig.start();
    f.npc.openGate = new CompletableFuture<>();
    var first = f.change(h, "npc");
    var latest = f.change(h, "hud");
    TestRig.done(latest);
    f.npc.openGate.complete(f.npc.handle());
    f.rig.scheduler.drain();
    assertTrue(first.toCompletableFuture().isDone());
    assertEquals(0, f.hud.closes);
    assertEquals(1, f.npc.closes);
    assertTrue(f.npc.frames.isEmpty());
    h.stop(DialogueEndReason.CANCELLED);
    f.rig.scheduler.drain();
  }

  @Test
  void i4RejectedNewRequestAlsoSupersedesOldCandidate() {
    var f = new Fixture();
    var h = f.rig.start();
    f.npc.openGate = new CompletableFuture<>();
    f.change(h, "npc");
    var rejected = f.change(h, "missing");
    assertTrue(rejected.toCompletableFuture().isCompletedExceptionally());
    f.npc.openGate.complete(f.npc.handle());
    f.rig.scheduler.drain();
    assertNotEquals(DialogueView.State.CLOSED, h.snapshot().state());
    assertEquals(0, f.hud.closes);
    assertEquals(1, f.npc.closes);
    assertTrue(f.npc.frames.isEmpty());
    h.stop(DialogueEndReason.CANCELLED);
    f.rig.scheduler.drain();
  }

  @Test
  void i5FreshDeadlineAndNonDefaultPreparationBudgetAreForwarded() {
    var f = new Fixture();
    var options =
        new RuntimeOptions(
            f.rig.scheduler,
            new RuntimeOptions.Budgets(
                Duration.ofSeconds(2),
                Duration.ofSeconds(9),
                Duration.ofSeconds(10),
                Duration.ofSeconds(7)),
            100,
            256);
    var runtime = DialogueRuntime.create(options);
    var result = runtime.start(f.rig.request("budget"));
    f.rig.scheduler.drain();
    var h = ((DialogueStartResult.Started) TestRig.done(result)).handle();
    f.rig.scheduler.advance(Duration.ofSeconds(60));
    f.npc.openGate = new CompletableFuture<>();
    var change = f.change(h, "npc");
    assertEquals(69_000_000_000L, f.npc.contexts.getLast().deadlineNanos());
    f.rig.scheduler.advance(Duration.ofSeconds(8));
    assertFalse(change.toCompletableFuture().isDone());
    f.npc.openGate.complete(f.npc.handle());
    f.rig.scheduler.drain();
    TestRig.done(change);
    h.stop(DialogueEndReason.CANCELLED);
    f.rig.scheduler.drain();
  }

  @Test
  void i5CleanupBudgetAlsoReachesTheNestedPresenter() {
    var f = new Fixture();
    var runtime =
        DialogueRuntime.create(
            new RuntimeOptions(
                f.rig.scheduler,
                new RuntimeOptions.Budgets(
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(9),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(7)),
                100,
                256));
    var result = runtime.start(f.rig.request("cleanup-budget"));
    f.rig.scheduler.drain();
    var h = ((DialogueStartResult.Started) TestRig.done(result)).handle();
    f.npc.openGate = new CompletableFuture<>();
    f.change(h, "npc");
    h.stop(DialogueEndReason.CANCELLED);
    f.rig.scheduler.drain();
    var ownClose = f.presentation.get().closeAsync();
    f.rig.scheduler.advance(Duration.ofSeconds(7));
    assertTrue(ownClose.toCompletableFuture().isCompletedExceptionally());
    f.npc.openGate.complete(f.npc.handle());
    f.rig.scheduler.drain();
    assertEquals(1, f.npc.closes);
  }

  @Test
  void i7CoalescingNeverAcknowledgesAnUndrawnFirstFrame() {
    for (boolean fail : List.of(false, true)) {
      var f = new Fixture();
      f.hud.firstWrite = new CompletableFuture<>();
      var start = f.rig.runtime.start(f.rig.request("first-frame"));
      f.rig.scheduler.drain();
      f.rig.scheduler.advance(Duration.ofMillis(70));
      assertFalse(start.toCompletableFuture().isDone(), "No frame has succeeded yet");
      assertEquals(1, f.hud.writes);
      if (fail) f.hud.firstWrite.completeExceptionally(new IllegalStateException("first draw"));
      else f.hud.firstWrite.complete(null);
      f.rig.scheduler.drain();
      if (fail) assertInstanceOf(DialogueStartResult.Rejected.class, TestRig.done(start));
      else {
        assertInstanceOf(DialogueStartResult.Started.class, TestRig.done(start));
        var h = ((DialogueStartResult.Started) TestRig.done(start)).handle();
        h.stop(DialogueEndReason.CANCELLED);
        f.rig.scheduler.drain();
      }
    }
  }

  @Test
  void i8PresenterObserversCannotCompleteOrCancelOwnedPromises() {
    var f = new Fixture();
    f.hud.openGate = new CompletableFuture<>();
    var request =
        new DialogueRenderer.PresentationRequest(
            UUID.randomUUID(),
            "viewer",
            1,
            PresentationPreferences.defaults(),
            new CancellationToken(),
            5_000_000_000L);
    var stage = f.presenter.prepare(request);
    stage.toCompletableFuture().cancel(false);
    f.rig.scheduler.drain();
    f.hud.openGate.complete(f.hud.handle());
    f.rig.scheduler.drain();
    var handle = TestRig.done(stage);
    var close = handle.closeAsync();
    close.toCompletableFuture().cancel(false);
    f.rig.scheduler.drain();
    TestRig.done(handle.closeAsync());
    assertEquals(1, f.hud.closes);
  }

  private record LiveHandoff(
      Fixture fixture,
      DialogueHandle handle,
      List<WeakReference<DialogueSurface.SurfaceHandle>> refs) {}

  private static LiveHandoff handoffHandles() {
    var f = new Fixture();
    var h = f.rig.start();
    var refs = new ArrayList<WeakReference<DialogueSurface.SurfaceHandle>>();
    boolean onHud = true;
    for (int i = 0; i < 20; i++) {
      refs.add((onHud ? f.hud : f.npc).lastHandle);
      TestRig.done(f.change(h, onHud ? "npc" : "hud"));
      onHud = !onHud;
    }
    return new LiveHandoff(f, h, refs);
  }

  @Test
  void i14ConfirmedOldHandlesAreCollectableBeforeSessionEnd() {
    var live = handoffHandles();
    try {
      assertTimeoutPreemptively(
          Duration.ofSeconds(3),
          () -> {
            while (live.refs().stream().anyMatch(ref -> ref.get() != null)) {
              System.gc();
              Thread.sleep(10);
            }
          });
    } finally {
      java.lang.ref.Reference.reachabilityFence(live);
      live.handle().stop(DialogueEndReason.CANCELLED);
      live.fixture().rig.scheduler.drain();
    }
  }

  @Test
  void i16SurfaceFrameReceivesActualThemePalette() {
    var f = new Fixture();
    var a =
        new DialogueTheme(
            "same", Map.of("text", 0x123456, "panel", 0xabcdef), 1.08, Duration.ofMillis(150));
    var b =
        new DialogueTheme(
            "same", Map.of("text", 0x654321, "panel", 0xfedcba), 1.08, Duration.ofMillis(150));
    var p =
        new DialoguePresenter(
            Map.of(
                "a",
                new DialoguePresenter.SurfaceProfile(
                    f.hud, LayoutProfile.hud(), a, FontMetrics.defaults(), false),
                "b",
                new DialoguePresenter.SurfaceProfile(
                    f.npc, LayoutProfile.hud(), b, FontMetrics.defaults(), false)),
            f.rig.scheduler);
    var h = f.rig.start();
    var first = p.compose(h.snapshot(), "a", PresentationPreferences.defaults());
    var second = p.compose(h.snapshot(), "b", PresentationPreferences.defaults());
    assertNotEquals(
        first, second, "A different configured palette must be observable by a standalone surface");
  }

  @Test
  void i5PreparationBudgetBoundsOldSurfaceReleaseToo() {
    var f = new Fixture();
    var h = f.rig.start();
    f.hud.closeGate = new CompletableFuture<>();
    var change = f.change(h, "npc");
    assertFalse(change.toCompletableFuture().isDone());
    f.rig.scheduler.advance(Duration.ofSeconds(5));
    assertTrue(change.toCompletableFuture().isCompletedExceptionally());
    assertNotEquals(DialogueView.State.WAITING_FOR_INPUT, h.snapshot().state());
    f.hud.closeGate.complete(null);
    f.rig.scheduler.drain();
    assertEquals(DialogueView.State.CLOSED, h.snapshot().state());
  }

  @Test
  void i7PreferenceAcknowledgementWaitsForActualReplacement() {
    var f = new Fixture();
    var h = f.rig.start();
    f.hud.writes = 0;
    f.hud.firstWrite = new CompletableFuture<>();
    var change = f.change(h, "hud");
    assertFalse(change.toCompletableFuture().isDone());
    f.rig.scheduler.advance(Duration.ofMillis(70));
    assertFalse(change.toCompletableFuture().isDone());
    f.hud.firstWrite.complete(null);
    f.rig.scheduler.drain();
    TestRig.done(change);
    h.stop(DialogueEndReason.CANCELLED);
    f.rig.scheduler.drain();
  }

  @Test
  void i8RuntimeShutdownObservationCannotCancelCleanup() {
    var f = new Fixture();
    var h = f.rig.start();
    f.hud.closeGate = new CompletableFuture<>();
    var shutdown = f.rig.runtime.closeAsync();
    shutdown.toCompletableFuture().cancel(false);
    f.rig.scheduler.drain();
    assertFalse(f.rig.runtime.closeAsync().toCompletableFuture().isDone());
    f.hud.closeGate.complete(null);
    f.rig.scheduler.drain();
    TestRig.done(f.rig.runtime.closeAsync());
    assertEquals(DialogueView.State.CLOSED, h.snapshot().state());
  }

  @Test
  void i13ProfilePinUsesCanonicalDataNotMapToString() {
    var f = new Fixture();
    var h = f.rig.start();
    assertEquals(
        "0068b520af32b07ce3973f1c4eda119740d5dcbf5259d3d972fb6eb822cbc361",
        f.hud.contexts.getFirst().profilePin());
    h.stop(DialogueEndReason.CANCELLED);
    f.rig.scheduler.drain();
  }
}
