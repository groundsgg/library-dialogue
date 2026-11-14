package gg.grounds.dialogue.core.port;

import java.time.Duration;

/** Host-owned monotonic clock and scheduler, kept alive until cleanup completes. */
public interface DialogueScheduler {
  void execute(Runnable command);

  ScheduledTask schedule(Duration delay, Runnable command);

  long nanoTime();

  @FunctionalInterface
  interface ScheduledTask {
    void cancel();
  }
}
