package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.internal.OwnedScope
import gg.grounds.dialogue.core.internal.SerialCommandQueue
import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.port.OwnedResource
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.minestom.MinestomConnectionScope
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.minestom.asset.PackStateTracker
import gg.grounds.dialogue.minestom.channel.OutputChannels
import gg.grounds.dialogue.minestom.input.SurfaceInputTargets
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueSurface
import gg.grounds.dialogue.presentation.WidgetFrame
import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component

/**
 * Native action-bar projection borrowing the player and instance from [scope]. The open
 * audience/host generation and frame session must match before fallback or acquisition. Owns its
 * HUD/effect leases; close retires input and asynchronously releases those leases.
 */
class HudDialogueSurface(
    private val scope: MinestomConnectionScope,
    private val packs: PackStateTracker,
    private val assets: AssetBundle,
    private val channels: OutputChannels,
    private val targets: SurfaceInputTargets,
    private val clock: DialogueScheduler,
    private val diagnostic: Consumer<String>,
) : DialogueSurface {
    private val player = scope.player
    private val generation = scope.generation

    internal fun requireOwner(scope: MinestomConnectionScope, targets: SurfaceInputTargets) {
        require(this.scope === scope && this.targets === targets) {
            "Native surface owner mismatch"
        }
    }

    private val encoder = MinecraftWidgetEncoder()

    private fun available(): Boolean =
        scope.isCurrent() && packs.isReady(player, generation, assets.pin())

    override fun open(
        context: DialogueSurface.SurfaceContext
    ): CompletionStage<DialogueSurface.SurfaceHandle> {
        if (
            context.audienceId != scope.player.uuid.toString() ||
                context.hostGeneration != scope.generation
        ) {
            return CompletableFuture.failedFuture(
                IllegalArgumentException("Native context owner mismatch")
            )
        }
        if (!available()) {
            return CompletableFuture.failedFuture(
                DialoguePresenter.FallbackRequired("MISSING_PACK")
            )
        }
        val handle = Handle(context)
        handle.begin()
        return handle.ready.minimalCompletionStage()
    }

    private inner class Handle(val context: DialogueSurface.SurfaceContext) :
        DialogueSurface.SurfaceHandle {
        val queue =
            SerialCommandQueue(clock) {
                context.cancellation.cancel(DialogueEndReason.FAILED)
                closeAsync()
            }
        val resources = OwnedScope(queue, clock, context.cleanupBudget)
        val closed = AtomicBoolean()
        val ready = CompletableFuture<DialogueSurface.SurfaceHandle>()
        val cleanup = CompletableFuture<Void>()
        val cleanupView: CompletionStage<Void> = cleanup.minimalCompletionStage()
        var hud: OutputChannels.HudLease? = null
        var effects: OutputChannels.EffectLease? = null
        var preferences: PresentationPreferences = context.preferences
        var refresh: DialogueScheduler.ScheduledTask? = null
        var last: Component? = null

        fun begin() {
            queue.execute {
                resources
                    .prepare {
                        CompletableFuture.completedFuture(
                            channels.acquireHud(player, generation, 50)
                        )
                    }
                    .whenComplete { lease, error ->
                        queue.execute {
                            if (error != null) {
                                failed(error)
                                return@execute
                            }
                            hud = lease
                            if (channels.supportedEffects()) {
                                resources
                                    .prepare {
                                        CompletableFuture.completedFuture(
                                            channels.acquireEffects(player, generation)
                                        )
                                    }
                                    .whenComplete { effect, failure ->
                                        queue.execute {
                                            if (failure != null) {
                                                failed(failure)
                                            } else {
                                                effects = effect
                                                ready.complete(this)
                                            }
                                        }
                                    }
                            } else {
                                ready.complete(this)
                            }
                        }
                    }
            }
        }

        fun failed(error: Throwable) {
            closeAsync().whenComplete { _, cleanupFailure ->
                ready.completeExceptionally(
                    if (cleanupFailure == null) error
                    else OwnedResource.PreparationFailure(cleanupFailure)
                )
            }
        }

        override fun configure(value: PresentationPreferences): CompletionStage<Void> {
            val result = CompletableFuture<Void>()
            queue.execute {
                if (closed.get()) result.completeExceptionally(CancellationException())
                else {
                    preferences = value
                    result.complete(null)
                }
            }
            return result.minimalCompletionStage()
        }

        override fun replace(frame: WidgetFrame): CompletionStage<Void> {
            if (frame.semantic().sessionId() != context.sessionId) {
                return CompletableFuture.failedFuture(
                    DialogueSurface.FrameRejected("Native frame session mismatch")
                )
            }
            val result = CompletableFuture<Void>()
            queue.execute {
                if (closed.get() || context.cancellation.isCancelled()) {
                    result.completeExceptionally(CancellationException())
                    return@execute
                }
                if (!available()) {
                    targets.invalidateCurrent(context.generation)
                    result.completeExceptionally(DialoguePresenter.FallbackRequired("MISSING_PACK"))
                    return@execute
                }
                try {
                    val encoded = encoder.encode(frame, assets)
                    val hide = VisualOptions.enabled(preferences, "hideHud", diagnostic)
                    val vignette = VisualOptions.enabled(preferences, "vignette", diagnostic)
                    VisualOptions.enabled(preferences, "npcBackdrop", diagnostic)
                    if (vignette && effects == null) diagnostic.accept("POST_EFFECT_UNSUPPORTED")
                    val component =
                        HudCoverage.compose(encoded.hud(), frame.canvasBounds().width, hide)
                    val effectWrite =
                        effects?.replace(
                            if (vignette) listOf(Key.key("dialogue:vignette")) else emptyList()
                        ) ?: CompletableFuture.completedFuture(null)
                    effectWrite
                        .thenCompose {
                            if (available()) requireNotNull(hud).replace(component)
                            else
                                CompletableFuture.failedFuture(
                                    DialoguePresenter.FallbackRequired("MISSING_PACK")
                                )
                        }
                        .whenComplete { _, error ->
                            queue.execute {
                                if (error != null) {
                                    result.completeExceptionally(error)
                                    return@execute
                                }
                                if (
                                    closed.get() ||
                                        !scope.isCurrent() ||
                                        context.cancellation.isCancelled()
                                ) {
                                    result.completeExceptionally(CancellationException())
                                    return@execute
                                }
                                if (!available()) {
                                    targets.invalidateCurrent(context.generation)
                                    result.completeExceptionally(
                                        DialoguePresenter.FallbackRequired("MISSING_PACK")
                                    )
                                    return@execute
                                }
                                val currentHud = requireNotNull(hud)
                                if (!currentHud.visible()) {
                                    targets.invalidateCurrent(context.generation)
                                    result.completeExceptionally(
                                        DialoguePresenter.FallbackRequired("OUTPUT_SUPPRESSED")
                                    )
                                    return@execute
                                }
                                last = component
                                val choices = LinkedHashMap<String, WidgetFrame.Rect>()
                                frame
                                    .widgets()
                                    .filter {
                                        it.kind == WidgetFrame.Kind.CHOICE && it.enabled
                                    }
                                    .forEach { choices[checkNotNull(it.choiceId)] = it.hitRect() }
                                targets.publish(
                                    frame,
                                    context.generation,
                                    choices,
                                ) {
                                    !closed.get() && available() && requireNotNull(hud).visible()
                                }
                                renew()
                                result.complete(null)
                            }
                        }
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                }
            }
            return result.minimalCompletionStage()
        }

        fun renew() {
            refresh?.cancel()
            refresh =
                clock.schedule(Duration.ofSeconds(1)) {
                    queue.execute {
                        if (
                            closed.get() || context.cancellation.isCancelled() || !scope.isCurrent()
                        ) {
                            return@execute
                        }
                        val current = last
                        if (available() && current != null) {
                            requireNotNull(hud).replace(current).whenComplete { _, error ->
                                if (error != null) {
                                    context.cancellation.cancel(DialogueEndReason.FAILED)
                                }
                            }
                        }
                        renew()
                    }
                }
        }

        override fun closeAsync(): CompletionStage<Void> {
            if (closed.compareAndSet(false, true)) {
                queue.execute {
                    refresh?.cancel()
                    last = null
                    targets.clear(context.generation)
                    resources.closeAsync().whenComplete { _, error ->
                        ready.completeExceptionally(CancellationException())
                        if (error == null) cleanup.complete(null)
                        else cleanup.completeExceptionally(error)
                    }
                }
            }
            return cleanupView
        }
    }
}
