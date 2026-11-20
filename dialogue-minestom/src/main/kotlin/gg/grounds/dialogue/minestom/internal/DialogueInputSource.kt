package gg.grounds.dialogue.minestom.internal

import gg.grounds.dialogue.core.runtime.InputResult
import gg.grounds.dialogue.minestom.input.TargetSnapshot
import gg.grounds.dialogue.minestom.render.NpcDialogueSurface
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import net.minestom.server.entity.Player

/** Core-authorized semantic inputs and separate presentation-only browsing capability. */
interface DialogueInputSource {
    fun effectiveSurface(): String = ""

    fun hiddenHotbar(): Boolean = false

    fun refreshOutput() {}

    fun browse(displayed: TargetSnapshot, start: Int): CompletionStage<InputResult> =
        CompletableFuture.completedFuture(InputResult.STALE)

    fun targets(): TargetSnapshot? = null

    fun npcGeometry(): NpcDialogueSurface.Geometry? = null

    fun resolveChat(
        player: Player,
        generation: Long,
        token: String,
    ): Optional<TargetSnapshot.Target>
}
