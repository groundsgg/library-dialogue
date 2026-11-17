package gg.grounds.dialogue.minestom.internal;

import gg.grounds.dialogue.core.port.DialogueScheduler;
import java.time.Duration;
import java.util.Objects;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.TaskSchedule;

/** Instance work remains schedulable until owned cleanup settles. */
public final class MinestomDialogueScheduler implements DialogueScheduler {
  private volatile Instance instance;

  public MinestomDialogueScheduler(Instance instance) {
    this.instance = Objects.requireNonNull(instance);
  }

  public void execute(Runnable runnable) {
    var target = instance;
    if (target == null) runnable.run();
    else target.scheduler().scheduleNextProcess(runnable);
  }

  public ScheduledTask schedule(Duration delay, Runnable runnable) {
    var target = Objects.requireNonNull(instance, "Scheduler released after confirmed cleanup");
    var task =
        target.scheduler().buildTask(runnable).delay(TaskSchedule.duration(delay)).schedule();
    return task::cancel;
  }

  /** After confirmed core close, terminal observers must not retain the old world. */
  public void releaseAfterCleanup() {
    instance = null;
  }

  public long nanoTime() {
    return System.nanoTime();
  }
}
