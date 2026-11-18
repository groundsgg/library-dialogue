package gg.grounds.dialogue.testkit;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ManualDialogueSchedulerTest {
  @Test
  void sameDeadlineIsFifoAcrossExecuteAndTimers() {
    var s = new ManualDialogueScheduler();
    var seen = new ArrayList<Integer>();
    s.execute(() -> seen.add(1));
    s.schedule(Duration.ZERO, () -> seen.add(2));
    s.execute(() -> seen.add(3));
    s.runUntilIdle();
    assertEquals(List.of(1, 2, 3), seen);
    assertEquals(0, s.nanoTime());
  }

  @Test
  void advanceVisitsDeadlinesChronologically() {
    var s = new ManualDialogueScheduler();
    var seen = new ArrayList<Long>();
    for (int n : List.of(10, 2, 5)) s.schedule(Duration.ofMillis(n), () -> seen.add(s.nanoTime()));
    s.runUntilIdle();
    assertTrue(seen.isEmpty());
    s.advanceBy(Duration.ofMillis(10));
    assertEquals(List.of(2_000_000L, 5_000_000L, 10_000_000L), seen);
    assertEquals(10_000_000L, s.nanoTime());
  }

  @Test
  void cancellationIsIdempotent() {
    var s = new ManualDialogueScheduler();
    var seen = new AtomicInteger();
    var task = s.schedule(Duration.ZERO, seen::incrementAndGet);
    task.cancel();
    task.cancel();
    s.runUntilIdle();
    assertEquals(0, seen.get());
  }

  @Test
  void negativeAndOverflowRejected() {
    var s = new ManualDialogueScheduler();
    assertThrows(IllegalArgumentException.class, () -> s.advanceBy(Duration.ofNanos(-1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> s.schedule(Duration.ofSeconds(Long.MAX_VALUE), () -> {}));
    s.advanceBy(Duration.ofNanos(Long.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> s.advanceBy(Duration.ofNanos(1)));
    assertThrows(IllegalArgumentException.class, () -> s.schedule(Duration.ofNanos(1), () -> {}));
    assertEquals(Long.MAX_VALUE, s.nanoTime());
  }

  @Test
  void immediateReschedulingSharesOneBudget() {
    var s = new ManualDialogueScheduler();
    var seen = new AtomicInteger();
    Runnable loop =
        new Runnable() {
          public void run() {
            seen.incrementAndGet();
            s.schedule(Duration.ZERO, () -> s.execute(this));
          }
        };
    s.execute(loop);
    var e = assertThrows(IllegalStateException.class, s::runUntilIdle);
    assertEquals("TESTKIT_TASK_BUDGET", e.getMessage());
    assertEquals(5000, seen.get());
    var s2 = new ManualDialogueScheduler();
    var total = new AtomicInteger();
    Runnable timer =
        new Runnable() {
          public void run() {
            total.incrementAndGet();
            s2.schedule(Duration.ZERO, this);
          }
        };
    s2.execute(timer);
    assertThrows(IllegalStateException.class, s2::runUntilIdle);
    assertEquals(10000, total.get());
  }

  @Test
  void reentrantAndConcurrentControllersFail() throws Exception {
    var s = new ManualDialogueScheduler();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    s.execute(
        () -> {
          assertEquals(
              "TESTKIT_REENTRANT",
              assertThrows(IllegalStateException.class, s::runUntilIdle).getMessage());
          entered.countDown();
          try {
            assertTrue(release.await(5, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            throw new AssertionError(e);
          }
        });
    try (var executor = Executors.newSingleThreadExecutor()) {
      var running = executor.submit(s::runUntilIdle);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      try {
        assertThrows(IllegalStateException.class, () -> s.advanceBy(Duration.ZERO));
      } finally {
        release.countDown();
      }
      running.get(5, TimeUnit.SECONDS);
    }
    s.runUntilIdle();
  }

  @Test
  void foreignCompletionCanEnqueue() throws Exception {
    var s = new ManualDialogueScheduler();
    var seen = new ArrayList<Integer>();
    try (var executor = Executors.newSingleThreadExecutor()) {
      executor.submit(() -> s.execute(() -> seen.add(1))).get(5, TimeUnit.SECONDS);
    }
    s.runUntilIdle();
    assertEquals(List.of(1), seen);
  }

  @Test
  void callbackFailureReleasesController() {
    var s = new ManualDialogueScheduler();
    s.execute(
        () -> {
          throw new IllegalStateException("boom");
        });
    assertThrows(IllegalStateException.class, () -> s.advanceBy(Duration.ofNanos(10)));
    assertEquals(0, s.nanoTime());
    s.advanceBy(Duration.ofNanos(2));
    assertEquals(2, s.nanoTime());
  }

  @Test
  void realRuntimeRevealAndCancelUsesVirtualTime() {
    var f = new RuntimeFixture();
    var views = new ArrayList<gg.grounds.dialogue.core.runtime.DialogueView>();
    gg.grounds.dialogue.core.port.DialogueRenderer renderer =
        req ->
            CompletableFuture.completedFuture(
                new gg.grounds.dialogue.core.port.DialogueRenderer.PresentationHandle() {
                  public CompletionStage<Void> replace(
                      gg.grounds.dialogue.core.runtime.DialogueView v) {
                    views.add(v);
                    return CompletableFuture.completedFuture(null);
                  }

                  public CompletionStage<Void> reconfigure(
                      gg.grounds.dialogue.core.runtime.PresentationPreferences p) {
                    return CompletableFuture.completedFuture(null);
                  }

                  public CompletionStage<Void> closeAsync() {
                    return CompletableFuture.completedFuture(null);
                  }
                });
    var h = f.start(renderer, "one");
    f.clock.advanceBy(Duration.ofSeconds(1));
    assertEquals(5, h.snapshot().revealedGraphemes());
    var stop = h.stop(gg.grounds.dialogue.core.runtime.DialogueEndReason.CANCELLED);
    f.clock.runUntilIdle();
    assertTrue(stop.toCompletableFuture().isDone());
    int before = views.size();
    f.clock.advanceBy(Duration.ofSeconds(10));
    assertEquals(before, views.size());
  }
}
