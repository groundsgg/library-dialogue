package gg.grounds.dialogue.minestom.input

import gg.grounds.dialogue.core.runtime.InputResult
import gg.grounds.dialogue.minestom.render.NpcDialogueSurface
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import net.minestom.server.entity.Player

/**
 * Public output/input bridge for a custom native pipeline, implemented by the native renderer.
 *
 * Read [targets] at input admission and use its exact session, revision, visit and surface
 * generation. [browse] changes only the displayed viewport; Core still authorizes semantic input.
 * [refreshOutput] requests another acknowledged frame without restarting Core. A missing target
 * snapshot is stale input, even if an older frame remains visible.
 *
 * Chat capabilities belong to the store which issued the output. A renderer constructed without
 * that store returns an empty [resolveChat] result; the application may resolve through the exact
 * surface's store instead. Custom surface wrappers and callbacks must preserve scope/registry
 * ownership themselves because their wrapped native components cannot be inspected.
 */
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
