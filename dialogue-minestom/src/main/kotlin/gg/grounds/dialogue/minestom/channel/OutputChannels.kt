package gg.grounds.dialogue.minestom.channel

import gg.grounds.dialogue.core.port.OwnedResource
import java.util.concurrent.CompletionStage
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player

/** Inject the application's shared coordinator when other plugins use the same native channels. */
interface OutputChannels {
    interface Lease<T> : OwnedResource {
        fun replace(value: T): CompletionStage<Void>
    }

    interface HudLease : Lease<Component> {
        /** Coordinators that suppress a registered source must report it to its surface. */
        fun visible(): Boolean = true
    }

    interface EffectLease : Lease<List<@JvmSuppressWildcards Key>>

    fun acquireHud(player: Player, generation: Long, priority: Int): HudLease

    fun acquireEffects(player: Player, generation: Long): EffectLease

    fun supportedEffects(): Boolean
}
