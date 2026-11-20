package gg.grounds.dialogue.minestom.internal

import java.util.concurrent.atomic.AtomicBoolean
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

/** The host supplies the local connection and its instance. */
class ConnectionGuard(
    private val player: Player,
    private val generation: Long,
    private val instance: Instance,
) {
    private val valid = AtomicBoolean(true)

    init {
        require(generation >= 0) { "generation" }
    }

    fun isCurrent(): Boolean = valid.get() && hasCurrentConnection()

    /**
     * Cleanup may repair current authoritative state after invalidation, within the same instance.
     */
    fun hasCurrentConnection(): Boolean = player.isOnline && player.instance === instance

    fun matches(player: Player, generation: Long): Boolean =
        this.player === player && this.generation == generation && isCurrent()

    fun invalidate() {
        valid.set(false)
    }
}
