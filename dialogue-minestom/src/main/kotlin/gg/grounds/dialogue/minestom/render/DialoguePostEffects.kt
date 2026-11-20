package gg.grounds.dialogue.minestom.render

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import net.kyori.adventure.key.Key
import net.minestom.server.entity.Player

/** Explicit transport capability; the public 26.2 profile has no native post-effect packet. */
@JvmSuppressWildcards
interface DialoguePostEffects {
    fun supported(): Boolean

    fun replace(player: Player, effects: List<Key>): CompletionStage<Void>

    companion object {
        @JvmStatic
        fun unsupported(): DialoguePostEffects =
            object : DialoguePostEffects {
                override fun supported(): Boolean = false

                override fun replace(player: Player, effects: List<Key>): CompletionStage<Void> =
                    CompletableFuture.failedFuture(
                        UnsupportedOperationException("POST_EFFECT_UNSUPPORTED")
                    )
            }
    }
}
