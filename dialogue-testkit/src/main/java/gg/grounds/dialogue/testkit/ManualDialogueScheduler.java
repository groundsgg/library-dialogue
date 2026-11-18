package gg.grounds.dialogue.testkit;

import gg.grounds.dialogue.core.port.DialogueScheduler;
import java.time.Duration;
import java.util.*;

/** Virtual time; callbacks run on the sole controller's thread, outside the admission lock. */
public final class ManualDialogueScheduler implements DialogueScheduler {
  private final Object lock = new Object();
  private final PriorityQueue<Job> queue =
      new PriorityQueue<>(
          Comparator.comparingLong((Job j) -> j.at).thenComparingLong(j -> j.sequence));
  private long now, sequence;
  private boolean controlling;

  private static final class Job {
    final long at, sequence;
    final Runnable callback;
    boolean cancelled;

    Job(long at, long sequence, Runnable callback) {
      this.at = at;
      this.sequence = sequence;
      this.callback = callback;
    }
  }

  public ManualDialogueScheduler() {}

  @Override
  public void execute(Runnable command) {
    schedule(Duration.ZERO, command);
  }

  @Override
  public ScheduledTask schedule(Duration delay, Runnable command) {
    Objects.requireNonNull(command);
    synchronized (lock) {
      long deadline = target(delay);
      if (sequence == Long.MAX_VALUE) throw new IllegalStateException("TESTKIT_SEQUENCE_EXHAUSTED");
      var job = new Job(deadline, sequence++, command);
      queue.add(job);
      return () -> {
        synchronized (lock) {
          job.cancelled = true;
        }
      };
    }
  }

  @Override
  public long nanoTime() {
    synchronized (lock) {
      return now;
    }
  }

  /** Runs only work due at the current virtual time. */
  public void runUntilIdle() {
    control(Duration.ZERO);
  }

  /** Visits due deadlines in order, then advances to the requested target. */
  public void advanceBy(Duration duration) {
    control(duration);
  }

  private long target(Duration duration) {
    Objects.requireNonNull(duration);
    if (duration.isNegative()) throw new IllegalArgumentException("Negative duration");
    try {
      return Math.addExact(now, duration.toNanos());
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Time overflow", e);
    }
  }

  private void control(Duration duration) {
    final long end;
    synchronized (lock) {
      if (controlling) throw new IllegalStateException("TESTKIT_REENTRANT");
      end = target(duration);
      controlling = true;
    }
    int count = 0;
    try {
      while (true) {
        Job next;
        synchronized (lock) {
          while (!queue.isEmpty() && queue.peek().cancelled) queue.remove();
          if (queue.isEmpty() || queue.peek().at > end) {
            now = end;
            return;
          }
          if (count == 10_000) throw new IllegalStateException("TESTKIT_TASK_BUDGET");
          next = queue.remove();
          now = next.at;
          count++;
        }
        next.callback.run();
      }
    } finally {
      synchronized (lock) {
        controlling = false;
      }
    }
  }
}
