package gg.grounds.dialogue.minestom.geometry

import gg.grounds.dialogue.core.definition.DialogueDefinition.Interaction
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import java.time.Duration
import java.util.Optional

/**
 * Independent distance and look timers with angle hysteresis. Clock zero is a valid first sample.
 */
class DialogueLeavePolicy(private val interaction: Interaction, distanceGrace: Duration) {
    private val distanceDelay = distanceGrace.toNanos()
    private val lookDelay = Duration.ofMillis(interaction.lookAway.exitDelayMs).toNanos()
    private var awaySince: Long? = null
    private var farSince: Long? = null

    init {
        if (
            distanceDelay < 0 ||
                lookDelay < 0 ||
                !interaction.maxDistanceBlocks.isFinite() ||
                interaction.maxDistanceBlocks <= 0
        ) {
            throw IllegalArgumentException("Invalid leave policy")
        }
    }

    fun observe(
        angle: Double,
        distance: Double,
        onVisibleAnswer: Boolean,
        now: Long,
    ): Optional<DialogueEndReason> {
        if (!angle.isFinite() || !distance.isFinite() || angle < 0 || angle > 180 || distance < 0) {
            return Optional.of(DialogueEndReason.HOST_CHANGED)
        }
        if (distance > interaction.maxDistanceBlocks) {
            if (farSince == null) farSince = now
            if (now - farSince!! >= distanceDelay)
                return Optional.of(DialogueEndReason.OUT_OF_RANGE)
        } else {
            farSince = null
        }
        val look = interaction.lookAway
        if (!look.enabled || onVisibleAnswer || angle < look.resetAngleDegrees) {
            awaySince = null
        } else if (angle > look.exitAngleDegrees && awaySince == null) {
            awaySince = now
        }
        if (awaySince != null && now - awaySince!! >= lookDelay) {
            return Optional.of(DialogueEndReason.LOOKED_AWAY)
        }
        return Optional.empty()
    }
}
