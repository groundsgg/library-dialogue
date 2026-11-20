package gg.grounds.dialogue.minestom.asset

import java.util.UUID
import java.util.WeakHashMap
import net.kyori.adventure.resource.ResourcePackStatus
import net.minestom.server.entity.Player

/** A pack must be registered before sending it; status packets cannot choose their own manifest. */
class PackStateTracker {
    private data class Expected(
        val generation: Long,
        val id: UUID,
        val pin: String,
        val ready: Boolean,
    )

    private val current = WeakHashMap<Player, Expected>()
    private val used = WeakHashMap<Player, MutableSet<UUID>>()

    private data class ProxyConfirmation(
        val generation: Long,
        val revision: Long,
        val pin: String,
        val ready: Boolean,
    )

    private val proxy = WeakHashMap<Player, ProxyConfirmation>()

    /** The application supplies confirmations from its authenticated proxy connection. */
    @Synchronized
    fun confirmProxyState(
        player: Player,
        generation: Long,
        revision: Long,
        pin: String,
        ready: Boolean,
    ): Boolean {
        require(generation >= 0 && revision > 0 && pin.matches(Regex("[a-f0-9]{64}"))) {
            "Invalid proxy pack confirmation"
        }
        val previous = proxy[player]
        if (
            previous != null && (revision <= previous.revision || generation < previous.generation)
        ) {
            return false
        }
        proxy[player] = ProxyConfirmation(generation, revision, pin, ready)
        return true
    }

    @Synchronized
    fun expect(player: Player, generation: Long, id: UUID, manifestPin: String) {
        require(generation >= 0) { "generation" }
        val old = current[player]
        if (old != null && generation < old.generation) {
            throw IllegalArgumentException("STALE_PACK_GENERATION")
        }
        if (old != null && old.id == id) {
            if (old.generation != generation || old.pin != manifestPin) {
                throw IllegalArgumentException("PACK_UUID_REUSED")
            }
            return
        }
        val history = used.computeIfAbsent(player) { HashSet() }
        if (id in history) throw IllegalArgumentException("PACK_UUID_REUSED")
        if (history.size >= 65536) throw IllegalArgumentException("PACK_REGISTER_FULL")
        history.add(id)
        current[player] = Expected(generation, id, manifestPin, false)
    }

    @Synchronized
    fun observe(
        player: Player,
        generation: Long,
        id: UUID,
        pin: String,
        status: ResourcePackStatus,
    ) {
        val expected = current[player]
        if (
            expected == null ||
                expected.generation != generation ||
                expected.id != id ||
                expected.pin != pin
        ) {
            return
        }
        current[player] =
            Expected(generation, id, pin, status == ResourcePackStatus.SUCCESSFULLY_LOADED)
    }

    @Synchronized
    fun observe(player: Player, id: UUID, status: ResourcePackStatus) {
        val expected = current[player]
        if (expected != null) observe(player, expected.generation, id, expected.pin, status)
    }

    @Synchronized
    fun isReady(player: Player, generation: Long, pin: String): Boolean {
        if (!player.isOnline) return false
        val confirmed = proxy[player]
        if (confirmed != null) {
            return confirmed.generation == generation && confirmed.pin == pin && confirmed.ready
        }
        val expected = current[player]
        return expected != null &&
            expected.generation == generation &&
            expected.pin == pin &&
            expected.ready
    }

    @Synchronized
    fun forget(player: Player) {
        current.remove(player)
        proxy.remove(player)
        used.remove(player)
    }

    @Synchronized
    fun clear() {
        current.clear()
        proxy.clear()
        used.clear()
    }
}
