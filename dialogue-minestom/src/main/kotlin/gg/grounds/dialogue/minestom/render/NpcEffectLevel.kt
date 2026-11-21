package gg.grounds.dialogue.minestom.render

import java.time.Duration

/** One queue-confined visual level, sampled from elapsed monotonic time. */
internal class NpcEffectLevel {
    var current = 0.0
        private set

    var enabled = false
        private set

    private var from = 0.0
    private var started = 0L
    private var durationNanos = 0L
    private val target: Double
        get() = if (enabled) 1.0 else 0.0

    fun retarget(
        enabled: Boolean,
        now: Long,
        duration: Duration,
        applied: Double = current,
    ): Boolean {
        if (this.enabled == enabled) return false
        this.enabled = enabled
        current = applied
        from = applied
        started = now
        durationNanos = if (from == target) 0 else duration.toNanos()
        if (durationNanos == 0L) current = target
        return true
    }

    val settled: Boolean
        get() = current == target

    fun remaining(now: Long): Long =
        if (settled) 0L else (durationNanos - (now - started)).coerceAtLeast(0)

    fun advance(now: Long) {
        if (current == target) return
        val elapsed = now - started
        current =
            if (elapsed >= durationNanos) target
            else from + (target - from) * elapsed.toDouble() / durationNanos
    }
}
