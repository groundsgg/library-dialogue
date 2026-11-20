package gg.grounds.dialogue.testkit

import gg.grounds.dialogue.core.port.DialogueScheduler
import java.time.Duration
import java.util.PriorityQueue

/** Virtual time. Callbacks run on the sole controller's thread, outside the admission lock. */
class ManualDialogueScheduler : DialogueScheduler {
    private val lock = Any()
    private val queue = PriorityQueue(compareBy<Job>({ it.at }, { it.sequence }))
    private var now = 0L
    private var sequence = 0L
    private var controlling = false

    private class Job(val at: Long, val sequence: Long, val callback: Runnable) {
        var cancelled = false
    }

    override fun execute(command: Runnable) {
        schedule(Duration.ZERO, command)
    }

    override fun schedule(delay: Duration, command: Runnable): DialogueScheduler.ScheduledTask =
        synchronized(lock) {
            val deadline = target(delay)
            check(sequence != Long.MAX_VALUE) { "TESTKIT_SEQUENCE_EXHAUSTED" }
            val job = Job(deadline, sequence++, command)
            queue.add(job)
            DialogueScheduler.ScheduledTask {
                synchronized(lock) { job.cancelled = true }
            }
        }

    override fun nanoTime(): Long = synchronized(lock) { now }

    /** Runs only work due at the current virtual time. */
    fun runUntilIdle() {
        control(Duration.ZERO)
    }

    /** Visits due deadlines in order, then advances to the requested target. */
    fun advanceBy(duration: Duration) {
        control(duration)
    }

    private fun target(duration: Duration): Long {
        require(!duration.isNegative) { "Negative duration" }
        return try {
            Math.addExact(now, duration.toNanos())
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("Time overflow", failure)
        }
    }

    private fun control(duration: Duration) {
        val end =
            synchronized(lock) {
                check(!controlling) { "TESTKIT_REENTRANT" }
                target(duration).also { controlling = true }
            }
        var count = 0
        try {
            while (true) {
                val next =
                    synchronized(lock) {
                        while (queue.peek()?.cancelled == true) queue.remove()
                        if (queue.isEmpty() || queue.peek().at > end) {
                            now = end
                            return
                        }
                        check(count < 10_000) { "TESTKIT_TASK_BUDGET" }
                        queue.remove().also {
                            now = it.at
                            count++
                        }
                    }
                next.callback.run()
            }
        } finally {
            synchronized(lock) { controlling = false }
        }
    }
}
