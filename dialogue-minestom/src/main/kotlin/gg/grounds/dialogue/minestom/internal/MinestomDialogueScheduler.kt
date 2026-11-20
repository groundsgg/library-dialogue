package gg.grounds.dialogue.minestom.internal

import gg.grounds.dialogue.core.port.DialogueScheduler
import java.time.Duration
import net.minestom.server.instance.Instance
import net.minestom.server.timer.TaskSchedule

/** Instance work remains schedulable until owned cleanup settles. */
class MinestomDialogueScheduler(instance: Instance) : DialogueScheduler {
    @Volatile private var instance: Instance? = instance

    override fun execute(runnable: Runnable) {
        val target = instance
        if (target == null) runnable.run() else target.scheduler().scheduleNextProcess(runnable)
    }

    override fun schedule(delay: Duration, runnable: Runnable): DialogueScheduler.ScheduledTask {
        val target =
            instance ?: throw NullPointerException("Scheduler released after confirmed cleanup")
        val task =
            target.scheduler().buildTask(runnable).delay(TaskSchedule.duration(delay)).schedule()
        return DialogueScheduler.ScheduledTask(task::cancel)
    }

    /** After confirmed core close, terminal observers must not retain the old world. */
    fun releaseAfterCleanup() {
        instance = null
    }

    override fun nanoTime(): Long = System.nanoTime()
}
