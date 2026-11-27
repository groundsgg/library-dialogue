package gg.grounds.dialogue.minestom.input

import gg.grounds.dialogue.core.port.OwnedResource
import gg.grounds.dialogue.minestom.MinestomConnectionScope
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.metadata.other.InteractionMeta

/**
 * One owned, viewer-only interaction volume in the connection scope's exact instance. Opening
 * retains ownership of a late spawn; invalidation before acknowledgement removes it. Close is
 * idempotent. The caller keeps the instance scheduler alive until acquisition/close settles.
 * Neither hand's item nor the borrowed player/instance is owned by this resource.
 */
class PrivateInteractionCatcher private constructor(private val scope: MinestomConnectionScope) :
    OwnedResource {
    private val player = scope.player
    private val entity = Entity(EntityType.INTERACTION)
    private val closed = AtomicBoolean()

    init {
        entity.isAutoViewable = false
        entity.setNoGravity(true)
        entity.setHasPhysics(false)
        val meta = entity.entityMeta as InteractionMeta
        meta.width = 32.0f
        meta.height = 32.0f
        meta.setResponse(true)
    }

    private fun position(): Pos = player.position.sub(0.0, .5, 0.0).withView(0.0f, 0.0f)

    fun publish() {
        if (!closed.get() && scope.isCurrent()) entity.addViewer(player)
    }

    fun follow() {
        if (!closed.get() && scope.isCurrent()) {
            val position = position()
            if (position != entity.position) entity.teleport(position)
        }
    }

    fun entityId(): Int = entity.entityId

    override fun closeAsync(): CompletionStage<Void> {
        if (closed.compareAndSet(false, true)) {
            entity.removeViewer(player)
            entity.remove()
        }
        return CompletableFuture.completedFuture(null)
    }

    companion object {
        @JvmStatic
        fun open(scope: MinestomConnectionScope): CompletionStage<PrivateInteractionCatcher> {
            val catcher = PrivateInteractionCatcher(scope)
            val ready = CompletableFuture<PrivateInteractionCatcher>()
            try {
                catcher.entity.setInstance(scope.instance, catcher.position()).whenComplete {
                    _,
                    error ->
                    if (error == null) {
                        if (scope.isCurrent()) ready.complete(catcher)
                        else {
                            catcher.closeAsync().whenComplete { _, _ ->
                                ready.completeExceptionally(CancellationException())
                            }
                        }
                    } else {
                        catcher.closeAsync().whenComplete { _, _ ->
                            ready.completeExceptionally(error)
                        }
                    }
                }
            } catch (error: Throwable) {
                catcher.closeAsync()
                ready.completeExceptionally(error)
            }
            return ready.minimalCompletionStage()
        }
    }
}
