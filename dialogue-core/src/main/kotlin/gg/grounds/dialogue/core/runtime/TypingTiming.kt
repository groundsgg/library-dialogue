package gg.grounds.dialogue.core.runtime

import java.util.UUID

/**
 * Immutable typing phase in the injected DialogueScheduler nanoTime clock. Absolute timestamps may
 * be negative. Compare durations by subtraction, including across wrap.
 */
class TypingTiming(
    private val sessionId: UUID,
    private val nodeVisitId: Long,
    private val renderRevision: Long,
    private val graphemeDelayNanos: Long,
    private val enteredNanos: Long,
    private val endNanos: Long,
) {
    fun sessionId(): UUID = sessionId

    fun nodeVisitId(): Long = nodeVisitId

    fun renderRevision(): Long = renderRevision

    fun graphemeDelayNanos(): Long = graphemeDelayNanos

    fun enteredNanos(): Long = enteredNanos

    fun endNanos(): Long = endNanos

    fun remainingNanos(now: Long): Long = (endNanos - now).coerceAtLeast(0)
}
