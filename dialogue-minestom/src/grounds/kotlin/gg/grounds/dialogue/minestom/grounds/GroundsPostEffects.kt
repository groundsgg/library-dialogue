package gg.grounds.dialogue.minestom.grounds

import gg.grounds.dialogue.minestom.render.DialoguePostEffects
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import net.kyori.adventure.key.Key
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.common.PostEffectsPacket

/** Compile only against the explicitly selected Grounds 26.3 profile. */
class GroundsPostEffects : DialoguePostEffects {
    override fun supported(): Boolean = true

    override fun replace(player: Player, effects: List<Key>): CompletionStage<Void> {
        player.sendPacket(PostEffectsPacket(effects.toList()))
        return CompletableFuture.completedFuture(null)
    }
}
