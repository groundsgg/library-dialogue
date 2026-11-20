package gg.grounds.dialogue.core.port

import java.time.Duration

/** Host-owned monotonic clock and scheduler, kept alive until cleanup completes. */
interface DialogueScheduler {
    fun execute(command: Runnable)

    fun schedule(delay: Duration, command: Runnable): ScheduledTask

    fun nanoTime(): Long

    fun interface ScheduledTask {
        fun cancel()
    }
}
