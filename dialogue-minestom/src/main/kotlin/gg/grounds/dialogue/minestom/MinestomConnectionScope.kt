package gg.grounds.dialogue.minestom

import java.util.concurrent.atomic.AtomicBoolean
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

/**
 * One dialogue session's local native connection lifetime, shared by every native component.
 *
 * Create a fresh scope, renderer and target registry for each session, even when the player,
 * generation and instance are unchanged. Ownership checks use this object's identity. The scope
 * borrows [player] and [instance]; it owns neither. [generation] must be nonnegative.
 *
 * Invalidation fences new native work. It does not prove Core completion or late resource release;
 * retain the scope and keep the scheduler available until asynchronous cleanup settles. Callers
 * stopping Core enter through Core's cancellation path; a completion observer can invalidate only
 * after Core completion. Native surfaces also retain their own close fences.
 */
class MinestomConnectionScope(
    val player: Player,
    val generation: Long,
    val instance: Instance,
) {
    private val valid = AtomicBoolean(true)

    init {
        require(generation >= 0) { "generation" }
    }

    /** True only while valid, online and in the exact owning instance. */
    fun isCurrent(): Boolean = valid.get() && hasCurrentConnection()

    /**
     * Cleanup may repair current authoritative state after invalidation, within the same instance.
     */
    fun hasCurrentConnection(): Boolean = player.isOnline && player.instance === instance

    /** Additionally requires the exact player reference and host generation. */
    fun matches(player: Player, generation: Long): Boolean =
        this.player === player && this.generation == generation && isCurrent()

    /** Idempotently fences work; current-connection restoration may still be possible. */
    fun invalidate() {
        valid.set(false)
    }
}
