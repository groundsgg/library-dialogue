package gg.grounds.dialogue.minestom.internal;

import gg.grounds.dialogue.core.port.DialogueScheduler;
import java.time.Duration;
import java.util.Objects;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.TaskSchedule;

/** Instance work remains schedulable until owned cleanup settles. */
public final class MinestomDialogueScheduler implements DialogueScheduler {
  private final Instance instance;

  public MinestomDialogueScheduler(Instance instance) {
    this.instance = Objects.requireNonNull(instance);
  }

  public void execute(Runnable runnable) {
    instance.scheduler().scheduleNextProcess(runnable);
  }

  public ScheduledTask schedule(Duration delay, Runnable runnable) {
    var task =
        instance.scheduler().buildTask(runnable).delay(TaskSchedule.duration(delay)).schedule();
    return task::cancel;
  }

  public long nanoTime() {
    return System.nanoTime();
  }
}
