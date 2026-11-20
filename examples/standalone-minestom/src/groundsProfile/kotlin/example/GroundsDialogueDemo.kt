package example

import gg.grounds.dialogue.minestom.render.DialoguePostEffects
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import net.kyori.adventure.key.Key
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.common.PostEffectsPacket

/** Opt-in application transport, compiled only against the pinned Grounds fork. */
object GroundsDialogueDemo {
    @JvmStatic
    fun effects(): DialoguePostEffects =
        object : DialoguePostEffects {
            override fun supported(): Boolean = true

            override fun replace(player: Player, keys: List<Key>): CompletionStage<Void> {
                player.sendPacket(PostEffectsPacket(keys.toList()))
                return CompletableFuture.completedFuture(null)
            }
        }

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("dialogue.profile", "grounds-26.3")
        DialogueDemo.run(args, DialogueDemo::nativeAnchor, emptyMap(), effects())
    }
}
