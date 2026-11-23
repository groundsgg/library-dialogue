package gg.grounds.dialogue.minestom.internal

import gg.grounds.dialogue.core.definition.DialogueDefinition
import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.port.HostBindings
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueHandle
import gg.grounds.dialogue.core.runtime.DialogueInput
import gg.grounds.dialogue.core.runtime.DialogueRuntime
import gg.grounds.dialogue.core.runtime.DialogueStart
import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.InputResult
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.runtime.RuntimeOptions
import gg.grounds.dialogue.minestom.DialogueServices
import gg.grounds.dialogue.minestom.MinestomDialogueOptions
import gg.grounds.dialogue.minestom.MinestomDialogueRequest
import gg.grounds.dialogue.minestom.MinestomSpeakerBinding
import gg.grounds.dialogue.minestom.geometry.DialogueLeavePolicy
import gg.grounds.dialogue.minestom.geometry.MinestomOcclusion
import gg.grounds.dialogue.minestom.geometry.NpcHitTest
import gg.grounds.dialogue.minestom.input.MinestomInputAdapter
import gg.grounds.dialogue.minestom.input.TargetSnapshot
import gg.grounds.dialogue.minestom.render.NpcDialogueSurface
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.BiConsumer
import java.util.function.Consumer
import net.kyori.adventure.sound.Sound
import net.minestom.server.event.player.PlayerPacketEvent
import net.minestom.server.sound.SoundEvent

/**
 * One concrete connection's owned core runtime. Shared NPCs and service objects remain borrowed.
 */
class MinestomDialogueSession(
    request: MinestomDialogueRequest,
    options: MinestomDialogueOptions,
    private val guard: ConnectionGuard,
    private val scheduler: MinestomDialogueScheduler,
    renderer: DialogueRenderer,
    private val token: CancellationToken,
    ready: Consumer<DialogueStartResult>,
    closed: BiConsumer<MinestomDialogueSession, Throwable?>,
) {
    private var request: MinestomDialogueRequest? = request
    private val runtime =
        DialogueRuntime.create(RuntimeOptions(scheduler, options.budgets, 65536, 256))
    private var renderer: DialogueRenderer? = renderer
    private var ready: Consumer<DialogueStartResult>? = ready
    private var closed: BiConsumer<MinestomDialogueSession, Throwable?>? = closed
    private val finishing = AtomicBoolean()
    private val impulse = AtomicLong(request.openingImpulse())
    private val inputs: MinestomInputAdapter
    @Volatile private var handle: DialogueHandle? = null
    @Volatile private var view: DialogueView? = null
    private var rejection: DialogueStartResult.Rejected? = null
    private var lastSpeaker: MinestomSpeakerBinding? =
        if (request.speakers().size == 1) request.speakers().values.first() else null
    private var sampledSpeaker: MinestomSpeakerBinding? = null
    private var leave: DialogueLeavePolicy? = null
    private val distanceGrace: Duration = options.distanceGrace
    private val typingSound = TypingSound()
    private var soundPreferences = request.preferences()
    private val soundEpoch = AtomicLong()
    private val manualReveals = java.util.concurrent.atomic.AtomicInteger()
    private val soundPlayer = request.player()
    private val soundInstance = request.player().instance

    init {
        inputs =
            MinestomInputAdapter(
                request.player(),
                request.hostGeneration(),
                guard,
                request.controls(),
                scheduler,
                token,
                object : MinestomInputAdapter.Target {
                    private fun source(): DialogueInputSource? =
                        this@MinestomDialogueSession.renderer as? DialogueInputSource

                    override fun view(): DialogueView? = this@MinestomDialogueSession.view

                    override fun targets(): TargetSnapshot? = source()?.targets()

                    override fun npcGeometry(): NpcDialogueSurface.Geometry? =
                        source()?.npcGeometry()

                    override fun effectiveSurface(): String = source()?.effectiveSurface() ?: ""

                    override fun hiddenHotbar(): Boolean = source()?.hiddenHotbar() == true

                    override fun nextImpulse(): Long = this@MinestomDialogueSession.nextImpulse()

                    override fun browse(
                        displayed: TargetSnapshot,
                        start: Int,
                    ): CompletionStage<InputResult> =
                        source()?.browse(displayed, start)
                            ?: CompletableFuture.completedFuture(InputResult.STALE)

                    override fun submit(input: DialogueInput): CompletionStage<InputResult> =
                        this@MinestomDialogueSession.submit(input)
                },
                NpcHitTest.OcclusionTest { from, to ->
                    MinestomOcclusion.visible(request.player().instance, from, to)
                },
                options.budgets,
            )
    }

    fun begin() {
        if (finishing.get()) return
        if (!guard.isCurrent() || token.isCancelled()) {
            cancel(
                if (token.isCancelled()) checkNotNull(token.reason())
                else DialogueEndReason.HOST_CHANGED
            )
            return
        }
        val currentRequest = request ?: return
        try {
            for (binding in currentRequest.speakers().values) {
                if (
                    binding.instance !== currentRequest.player().instance ||
                        !binding.snapshot.get().valid
                ) {
                    cancel(DialogueEndReason.HOST_CHANGED)
                    return
                }
            }
        } catch (_: Throwable) {
            cancel(DialogueEndReason.HOST_CHANGED)
            return
        }
        val services: DialogueServices = currentRequest.services()
        val decorated = DialogueRenderer { prepare ->
            requireNotNull(renderer).prepare(prepare).thenApply { surface ->
                object : DialogueRenderer.PresentationHandle {
                    override fun replace(value: DialogueView): CompletionStage<Void> {
                        if (!guard.isCurrent() || token.isCancelled()) {
                            return CompletableFuture.failedFuture(
                                CancellationException("Connection expired")
                            )
                        }
                        view = value
                        if (!validAnchor()) {
                            cancel(DialogueEndReason.HOST_CHANGED)
                            return CompletableFuture.failedFuture(
                                CancellationException("Anchor expired")
                            )
                        }
                        val epoch = soundEpoch.incrementAndGet()
                        val suppressed = manualReveals.get() > 0
                        return surface.replace(value).thenRun {
                            scheduler.execute {
                                if (
                                    soundEpoch.get() != epoch ||
                                        view !== value ||
                                        finishing.get() ||
                                        !guard.isCurrent() ||
                                        token.isCancelled() ||
                                        soundPlayer.instance !== soundInstance
                                )
                                    return@execute
                                val effective =
                                    (renderer as? DialogueInputSource)?.effectiveSurface()
                                val enabled =
                                    soundPreferences.effects()["typingSound"] == "true" &&
                                        effective in setOf("hud", "npc")
                                if (
                                    typingSound.observe(
                                        value,
                                        scheduler.nanoTime(),
                                        suppressed || !enabled,
                                    )
                                ) {
                                    soundPlayer.playSound(
                                        Sound.sound(
                                            SoundEvent.BLOCK_NOTE_BLOCK_HAT,
                                            Sound.Source.MASTER,
                                            0.1f,
                                            1.7f,
                                        )
                                    )
                                }
                            }
                        }
                    }

                    override fun reconfigure(
                        preferences: PresentationPreferences
                    ): CompletionStage<Void> {
                        soundEpoch.incrementAndGet()
                        return surface.reconfigure(preferences).thenCompose {
                            val applied = CompletableFuture<Void>()
                            scheduler.execute {
                                if (!finishing.get() && guard.isCurrent() && !token.isCancelled()) {
                                    soundPreferences = preferences
                                    typingSound.reset()
                                }
                                applied.complete(null)
                            }
                            applied
                        }
                    }

                    override fun closeAsync(): CompletionStage<Void> {
                        soundEpoch.incrementAndGet()
                        return surface.closeAsync()
                    }
                }
            }
        }
        val bindings =
            HostBindings(
                services.generation(),
                services.conditions(),
                services.actions(),
                services.outcomes(),
                services.texts(),
                decorated,
                currentRequest.actors(),
                services.capabilities(),
            )
        val requestedPreferences = currentRequest.preferences()
        val preferences =
            if (requestedPreferences.surfaceKey() == "chat") {
                PresentationPreferences(
                    "chat",
                    Duration.ZERO,
                    requestedPreferences.reducedMotion(),
                    requestedPreferences.effects(),
                )
            } else {
                requestedPreferences
            }
        runtime
            .start(
                DialogueStart(
                    currentRequest.requestId(),
                    currentRequest.player().uuid.toString(),
                    currentRequest.hostGeneration(),
                    currentRequest.content(),
                    bindings,
                    currentRequest.context(),
                    currentRequest.locale(),
                    preferences,
                    token,
                    currentRequest.openingImpulse(),
                )
            )
            .whenComplete { result, error ->
                if (error != null) {
                    finish(error)
                } else if (result is DialogueStartResult.Started) {
                    val running = result.handle
                    handle = running
                    running.completion().whenComplete { _, failure -> finish(failure) }
                    inputs.activate().whenComplete { _, failure ->
                        if (failure != null) {
                            finish(failure)
                        } else if (!finishing.get() && guard.isCurrent() && !token.isCancelled()) {
                            ready?.accept(result)
                        }
                    }
                } else {
                    rejection = result as DialogueStartResult.Rejected
                    finish(null)
                }
            }
    }

    private fun currentBinding(): MinestomSpeakerBinding? {
        val currentRequest = request ?: return null
        val currentView = view
        if (currentView != null) {
            val node = currentRequest.content().definition().nodes()[currentView.nodeId()]
            if (node is DialogueDefinition.Message) {
                lastSpeaker = currentRequest.speakers()[node.speaker()]
            }
        }
        return lastSpeaker
    }

    private fun validAnchor(): Boolean {
        val currentRequest = request ?: return true
        val binding = currentBinding() ?: return currentRequest.preferences().surfaceKey() != "npc"
        return try {
            binding.instance === currentRequest.player().instance && binding.snapshot.get().valid
        } catch (_: Throwable) {
            false
        }
    }

    fun packet(event: PlayerPacketEvent) {
        inputs.receive(event)
    }

    fun tickInput() {
        inputs.tick()
    }

    fun checkConnection() {
        if (finishing.get()) return
        if (!guard.isCurrent() || !validAnchor()) {
            cancel(DialogueEndReason.HOST_CHANGED)
            return
        }
        val currentRequest = request ?: return
        val binding = currentBinding() ?: return
        try {
            val anchor = binding.snapshot.get()
            if (!anchor.valid) {
                cancel(DialogueEndReason.HOST_CHANGED)
                return
            }
            if (sampledSpeaker !== binding) {
                sampledSpeaker = binding
                leave =
                    DialogueLeavePolicy(
                        currentRequest.content().definition().interaction(),
                        distanceGrace,
                    )
            }
            val player = currentRequest.player()
            val eye = player.position.add(0.0, player.eyeHeight.toDouble(), 0.0)
            val toward = anchor.lookOrigin.sub(eye).asVec()
            val angle =
                if (toward.lengthSquared() < 1e-12) 0.0
                else
                    Math.toDegrees(
                        Math.acos(
                            Math.clamp(
                                player.position.direction().dot(toward) / toward.length(),
                                -1.0,
                                1.0,
                            )
                        )
                    )
            val distance = player.position.distance(anchor.feet)
            var onFront = false
            val source = renderer as? DialogueInputSource
            if (source != null) {
                val geometry = source.npcGeometry()
                val targets = source.targets()
                if (
                    geometry != null &&
                        targets != null &&
                        geometry.surfaceGeneration() == targets.surfaceGeneration()
                ) {
                    onFront =
                        NpcHitTest.inDialogueZone(
                            eye,
                            player.position.direction(),
                            geometry.transform(),
                            geometry.fronts(),
                            NpcHitTest.OcclusionTest { from, to ->
                                MinestomOcclusion.visible(player.instance, from, to)
                            },
                        )
                }
            }
            leave?.observe(angle, distance, onFront, scheduler.nanoTime())?.ifPresent(::cancel)
        } catch (_: Throwable) {
            cancel(DialogueEndReason.HOST_CHANGED)
        }
    }

    fun cancel(reason: DialogueEndReason) {
        guard.invalidate()
        try {
            token.cancel(reason)
        } finally {
            finish(null)
        }
    }

    fun snapshot(): DialogueView? = view

    fun refreshPresentation() {
        (renderer as? DialogueInputSource)?.refreshOutput()
    }

    fun chat(value: String) {
        val currentRequest = request ?: return
        val current = view ?: return
        if (!guard.isCurrent()) return
        val source = renderer as? DialogueInputSource ?: return
        source
            .resolveChat(currentRequest.player(), currentRequest.hostGeneration(), value)
            .ifPresent { target ->
                if (!matchesChat(target, current)) return@ifPresent
                if (
                    current.state() == DialogueView.State.REVEALING ||
                        target.choiceId == null ||
                        target.choiceId == current.selectedChoiceId()
                ) {
                    submit(
                        DialogueInput.Confirm(
                            DialogueInput.InputStamp(
                                target.sessionId,
                                target.renderRevision,
                                nextImpulse(),
                            ),
                            target.choiceId,
                        )
                    )
                    return@ifPresent
                }
                submit(
                        DialogueInput.Select(
                            DialogueInput.InputStamp(
                                target.sessionId,
                                target.renderRevision,
                                nextImpulse(),
                            ),
                            target.choiceId,
                        )
                    )
                    .thenAccept { result ->
                        val now = view
                        if (
                            result == InputResult.ACCEPTED &&
                                guard.isCurrent() &&
                                now != null &&
                                matchesChat(target, now) &&
                                target.choiceId == now.selectedChoiceId()
                        ) {
                            submit(
                                DialogueInput.Confirm(
                                    DialogueInput.InputStamp(
                                        target.sessionId,
                                        target.renderRevision,
                                        nextImpulse(),
                                    ),
                                    target.choiceId,
                                )
                            )
                        }
                    }
            }
    }

    private fun matchesChat(target: TargetSnapshot.Target, current: DialogueView): Boolean =
        target.sessionId == current.sessionId() &&
            target.nodeVisitId == current.nodeVisitId() &&
            target.renderRevision == current.renderRevision()

    fun nextImpulse(): Long = impulse.incrementAndGet()

    fun submit(input: DialogueInput): CompletionStage<InputResult> {
        val current = view
        val stamp = (input as? DialogueInput.Confirm)?.stamp
        val suppress =
            current?.state() == DialogueView.State.REVEALING &&
                (stamp?.let {
                    it.sessionId == current.sessionId() &&
                        it.renderRevision == current.renderRevision()
                } == true ||
                    input is DialogueInput.RevealFinished &&
                        input.sessionId == current.sessionId() &&
                        input.renderRevision == current.renderRevision())
        if (suppress) manualReveals.incrementAndGet()
        return try {
            (handle?.submit(input) ?: CompletableFuture.completedFuture(InputResult.BUSY))
                .whenComplete { _, _ -> if (suppress) manualReveals.decrementAndGet() }
        } catch (failure: Throwable) {
            if (suppress) manualReveals.decrementAndGet()
            throw failure
        }
    }

    private fun finish(initialError: Throwable?) {
        if (!finishing.compareAndSet(false, true)) return
        soundEpoch.incrementAndGet()
        guard.invalidate()
        CompletableFuture.allOf(
                runtime.closeAsync().toCompletableFuture(),
                inputs.closeAsync().toCompletableFuture(),
            )
            .whenComplete { _, cleanup ->
                val failure = cleanup ?: initialError
                val terminal = rejection
                if (terminal != null) {
                    ready?.accept(
                        if (failure == null) terminal
                        else DialogueStartResult.Rejected("CLEANUP_FAILED", emptyList())
                    )
                }
                if (failure == null) scheduler.releaseAfterCleanup()
                val callback = closed
                closed = null
                ready = null
                if (failure == null) {
                    request = null
                    renderer = null
                    view = null
                    handle = null
                    lastSpeaker = null
                    sampledSpeaker = null
                    leave = null
                }
                callback?.accept(this, failure)
            }
    }
}
