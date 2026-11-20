package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.port.DialogueScheduler
import java.time.Duration
import java.util.ArrayDeque
import java.util.PriorityQueue

internal class ManualScheduler : DialogueScheduler {
    private var now = 0L
    private var sequence = 0L
    private val ready: ArrayDeque<Runnable> = ArrayDeque()
    private val jobs = PriorityQueue(compareBy<Job> { it.at }.thenBy { it.sequence })

    private class Job(val at: Long, val sequence: Long, val runnable: Runnable) {
        var cancelled = false
    }

    override fun execute(command: Runnable) {
        ready.add(command)
    }

    override fun schedule(delay: Duration, command: Runnable): DialogueScheduler.ScheduledTask {
        val job = Job(Math.addExact(now, delay.toNanos()), sequence++, command)
        jobs.add(job)
        return DialogueScheduler.ScheduledTask { job.cancelled = true }
    }

    override fun nanoTime(): Long = now

    fun drain() {
        var limit = 10_000
        while (ready.isNotEmpty()) {
            if (--limit == 0) throw AssertionError("nonterminating ready queue")
            ready.remove().run()
        }
    }

    fun advance(duration: Duration) {
        val target = Math.addExact(now, duration.toNanos())
        drain()
        while (jobs.isNotEmpty() && jobs.peek().at <= target) {
            val job = jobs.remove()
            now = job.at
            if (!job.cancelled) job.runnable.run()
            drain()
        }
        now = target
        drain()
    }

    fun advanceBatched(duration: Duration) {
        val target = Math.addExact(now, duration.toNanos())
        drain()
        now = target
        while (jobs.isNotEmpty() && jobs.peek().at <= target) {
            val job = jobs.remove()
            if (!job.cancelled) job.runnable.run()
        }
        drain()
    }
}
