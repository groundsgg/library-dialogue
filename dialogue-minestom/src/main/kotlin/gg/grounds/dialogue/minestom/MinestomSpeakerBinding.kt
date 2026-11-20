package gg.grounds.dialogue.minestom

import java.util.function.Supplier
import net.minestom.server.coordinate.Point
import net.minestom.server.instance.Instance

/** Borrowed body anchor. The host does not own the shared NPC or its collision entity. */
@JvmRecord
data class MinestomSpeakerBinding(
    val generation: String,
    val instance: Instance,
    val snapshot: Supplier<AnchorSnapshot>,
) {
    init {
        require(generation.isNotBlank()) { "generation" }
    }

    @JvmRecord
    data class AnchorSnapshot(
        val feet: Point,
        val bodyYaw: Float,
        val lookOrigin: Point,
        val valid: Boolean,
    ) {
        init {
            require(
                feet.x().isFinite() &&
                    feet.y().isFinite() &&
                    feet.z().isFinite() &&
                    bodyYaw.isFinite() &&
                    lookOrigin.x().isFinite() &&
                    lookOrigin.y().isFinite() &&
                    lookOrigin.z().isFinite()
            ) {
                "INVALID_ANCHOR"
            }
        }
    }
}
