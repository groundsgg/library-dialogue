package gg.grounds.dialogue.minestom.input

import java.time.Duration

/** Quiet-period heuristic, not a claim to detect physical key release. */
class ClickBurstGate(quiet: Duration) {
    private val quietNanos = quiet.toNanos()
    private var last = 0L
    private var seen = false

    init {
        require(quietNanos > 0) { "quiet" }
    }

    fun opened(now: Long) {
        last = now
        seen = true
    }

    fun accept(now: Long): Boolean {
        val accept = !seen || now - last >= quietNanos
        last = now
        seen = true
        return accept
    }
}
