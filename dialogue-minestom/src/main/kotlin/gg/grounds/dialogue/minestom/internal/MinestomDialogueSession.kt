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
import gg.grounds.dialogue.core.text.Graphemes
import gg.grounds.dialogue.minestom.DialogueServices
import gg.grounds.dialogue.minestom.MinestomDialogueOptions
import gg.grounds.dialogue.minestom.MinestomDialogueRequest
import gg.grounds.dialogue.minestom.MinestomDialogueSpeechEvent
import gg.grounds.dialogue.minestom.MinestomDialogueSpeechListener
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
    private val speechListener = request.speechListener()
    private var speechEnabled = speechListener !== MinestomDialogueSpeechListener.NO_OP
    private var speechActive = false
    private var speechIdentity: SpeechIdentity? = null
    private var speechRevealed = 0
    private var speechGraphemes: List<String> = emptyList()
    private var speechTerminalDelivered = false

    private data class SpeechIdentity(
        val sessionId: java.util.UUID,
        val nodeVisitId: Long,
        val nodeId: String,
        val localeTag: String,
        val text: String,
        val role: String,
        val speaker: MinestomSpeakerBinding,
    )

    private class SelectionReceipt(val before: DialogueView, val choice: String) {
        var accepted = false
        var presented: DialogueView? = null
        var epoch = -1L
    }

    private var selectionReceipt: SelectionReceipt? = null
    private var lastChoiceSoundNanos: Long? = null

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

                    // Adapter routing and hover work already run on the instance owner queue.
                    override fun submit(input: DialogueInput): CompletionStage<InputResult> =
                        this@MinestomDialogueSession.submitOwned(input)
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
                        observeSpeechCandidate(value, epoch)
                        val selection = selectionReceipt?.takeIf {
                            value.sessionId() == it.before.sessionId() &&
                                value.nodeVisitId() == it.before.nodeVisitId() &&
                                value.renderRevision() == it.before.renderRevision() &&
                                value.stateRevision() > it.before.stateRevision() &&
                                value.selectedChoiceId() == it.choice
                        }
                        val replacement = surface.replace(value)
                        replacement.whenComplete { _, failure ->
                            if (failure != null && speechEnabled) {
                                scheduler.execute {
                                    if (
                                        soundEpoch.get() == epoch &&
                                            view === value &&
                                            !finishing.get() &&
                                            guard.isCurrent() &&
                                            !token.isCancelled() &&
                                            !speechTerminalDelivered
                                    ) {
                                        stopSpeech(
                                            MinestomDialogueSpeechListener.StopReason
                                                .PRESENTATION_FAILED
                                        )
                                    }
                                }
                            }
                        }
                        return replacement.thenRun {
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
                                observeSpeechPresented(value, suppressed)
                                if (selection != null && selectionReceipt === selection) {
                                    selection.presented = value
                                    selection.epoch = epoch
                                    playSelection(selection)
                                }
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
                        if (speechEnabled) {
                            scheduler.execute {
                                if (!finishing.get() && guard.isCurrent() && !token.isCancelled()) {
                                    stopSpeech(
                                        MinestomDialogueSpeechListener.StopReason.CONTENT_REPLACED
                                    )
                                    speechIdentity = null
                                    speechRevealed = 0
                                    speechGraphemes = emptyList()
                                }
                            }
                        }
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
                        if (speechEnabled) {
                            scheduler.execute {
                                stopSpeech(MinestomDialogueSpeechListener.StopReason.PAUSED)
                            }
                        }
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
                    running.completion().whenComplete { end, failure ->
                        finish(failure, end?.reason() ?: token.reason())
                    }
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

    private fun speechIdentity(value: DialogueView): SpeechIdentity? {
        if (value.state() != DialogueView.State.REVEALING) return null
        val currentRequest = request ?: return null
        val message =
            currentRequest.content().definition().nodes()[value.nodeId()]
                as? DialogueDefinition.Message ?: return null
        val binding = currentRequest.speakers()[message.speaker()] ?: return null
        return SpeechIdentity(
            value.sessionId(),
            value.nodeVisitId(),
            value.nodeId(),
            value.localeTag(),
            value.text(),
            message.speaker(),
            binding,
        )
    }

    private fun observeSpeechCandidate(value: DialogueView, epoch: Long) {
        if (!speechEnabled) return
        val candidate = speechIdentity(value)
        val effective = (renderer as? DialogueInputSource)?.effectiveSurface()
        scheduler.execute {
            if (soundEpoch.get() != epoch || view !== value || finishing.get()) return@execute
            val reason =
                when {
                    value.state() != DialogueView.State.REVEALING ->
                        MinestomDialogueSpeechListener.StopReason.PAUSED
                    effective != "npc" || candidate == null ->
                        MinestomDialogueSpeechListener.StopReason.PRESENTATION_FALLBACK
                    speechIdentity != null && speechIdentity != candidate ->
                        MinestomDialogueSpeechListener.StopReason.CONTENT_REPLACED
                    else -> null
                }
            if (reason != null) stopSpeech(reason)
            if (candidate == null || effective != "npc" || speechIdentity != candidate) {
                speechIdentity = null
                speechRevealed = 0
                speechGraphemes = emptyList()
            }
        }
    }

    private fun observeSpeechPresented(value: DialogueView, suppressed: Boolean) {
        if (!speechEnabled || speechTerminalDelivered) return
        val candidate = speechIdentity(value) ?: return
        if ((renderer as? DialogueInputSource)?.effectiveSurface() != "npc") {
            stopSpeech(MinestomDialogueSpeechListener.StopReason.PRESENTATION_FALLBACK)
            speechIdentity = null
            speechRevealed = 0
            speechGraphemes = emptyList()
            return
        }
        if (speechIdentity != candidate) {
            speechIdentity = candidate
            speechRevealed = value.revealedGraphemes()
            speechGraphemes = Graphemes.split(value.text())
            return
        }
        val graphemes = speechGraphemes
        val current = value.revealedGraphemes().coerceIn(0, graphemes.size)
        val from = speechRevealed.coerceIn(0, current)
        speechRevealed = current
        if (current <= from || suppressed) return
        if (!speechActive) speechActive = true
        try {
            speechListener.onRevealed(
                MinestomDialogueSpeechEvent(
                    value,
                    candidate.role,
                    candidate.speaker,
                    graphemes.subList(from, current).joinToString(""),
                )
            )
        } catch (_: Throwable) {
            speechEnabled = false
            speechIdentity = null
            speechRevealed = 0
            speechGraphemes = emptyList()
            speechActive = false
            try {
                speechListener.onStop(MinestomDialogueSpeechListener.StopReason.LISTENER_FAILED)
            } catch (_: Throwable) {
                // Speech hooks are isolated from the dialogue runtime.
            }
        }
    }

    private fun stopSpeech(
        reason: MinestomDialogueSpeechListener.StopReason,
        terminal: Boolean = false,
    ) {
        if (speechListener === MinestomDialogueSpeechListener.NO_OP) return
        if (terminal) {
            if (speechTerminalDelivered) return
            speechTerminalDelivered = true
        } else if (!speechActive) return
        speechActive = false
        if (!speechEnabled && !terminal) return
        try {
            speechListener.onStop(reason)
        } catch (_: Throwable) {
            speechEnabled = false
            if (!terminal) {
                try {
                    speechListener.onStop(MinestomDialogueSpeechListener.StopReason.LISTENER_FAILED)
                } catch (_: Throwable) {
                    // Speech hooks are isolated from the dialogue runtime.
                }
            }
        }
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
        val result = CompletableFuture<InputResult>()
        scheduler.execute {
            try {
                submitOwned(input).whenComplete { value, failure ->
                    if (failure != null) result.completeExceptionally(failure)
                    else result.complete(value)
                }
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        return result
    }

    private fun choiceAudioEnabled(): Boolean =
        soundPreferences.effects()["choiceSounds"] == "true" &&
            (renderer as? DialogueInputSource)?.effectiveSurface() in setOf("hud", "npc")

    private fun playSelection(receipt: SelectionReceipt) {
        val presented = receipt.presented ?: return
        if (!receipt.accepted || selectionReceipt !== receipt) return
        selectionReceipt = null
        if (
            view !== presented ||
                soundEpoch.get() != receipt.epoch ||
                finishing.get() ||
                !guard.isCurrent() ||
                token.isCancelled() ||
                !choiceAudioEnabled()
        )
            return
        val now = scheduler.nanoTime()
        if (lastChoiceSoundNanos?.let { now - it < Duration.ofMillis(85).toNanos() } == true) return
        lastChoiceSoundNanos = now
        playChoiceSound(0.08f, 1.4f)
    }

    private fun playChoiceSound(volume: Float, pitch: Float) {
        soundPlayer.playSound(
            Sound.sound(SoundEvent.UI_BUTTON_CLICK, Sound.Source.MASTER, volume, pitch)
        )
    }

    private fun submitOwned(input: DialogueInput): CompletionStage<InputResult> {
        val current = view
        val eligible =
            current != null &&
                !finishing.get() &&
                guard.isCurrent() &&
                !token.isCancelled() &&
                choiceAudioEnabled()
        val selection =
            (input as? DialogueInput.Select)
                ?.takeIf {
                    eligible && it.choiceId != null && it.choiceId != current.selectedChoiceId()
                }
                ?.let { SelectionReceipt(current!!, it.choiceId!!) }
        selectionReceipt = selection
        val confirmation =
            (input as? DialogueInput.Confirm)?.takeIf { confirm ->
                eligible &&
                    current.state() == DialogueView.State.WAITING_FOR_INPUT &&
                    current.hint() == DialogueView.HintPhase.CHOOSE &&
                    confirm.choiceId != null &&
                    current.selectedChoiceId() == confirm.choiceId &&
                    current.choices().any {
                        it.id == confirm.choiceId &&
                            it.availability == DialogueView.Availability.AVAILABLE
                    }
            }

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
                .whenComplete { accepted, failure ->
                    if (suppress) manualReveals.decrementAndGet()
                    if (failure == null && accepted == InputResult.ACCEPTED) {
                        if (selection != null && selectionReceipt === selection) {
                            selection.accepted = true
                            playSelection(selection)
                        }
                        if (
                            confirmation != null &&
                                soundPlayer.instance === soundInstance &&
                                guard.hasCurrentConnection() &&
                                ((!finishing.get() && guard.isCurrent() && !token.isCancelled()) ||
                                    token.reason() == DialogueEndReason.COMPLETED)
                        ) {
                            // This callback runs inline on the core owner queue at accepted commit.
                            // Normal completion invalidates the guard before returning ACCEPTED.
                            playChoiceSound(0.14f, 1.0f)
                        }
                    } else if (selectionReceipt === selection) selectionReceipt = null
                }
        } catch (failure: Throwable) {
            if (suppress) manualReveals.decrementAndGet()
            throw failure
        }
    }

    private fun finish(
        initialError: Throwable?,
        terminalReason: DialogueEndReason? = token.reason(),
    ) {
        if (!finishing.compareAndSet(false, true)) return
        soundEpoch.incrementAndGet()
        guard.invalidate()
        val speechStop =
            when {
                terminalReason == DialogueEndReason.DISCONNECTED ->
                    MinestomDialogueSpeechListener.StopReason.DISCONNECTED
                initialError != null || terminalReason == DialogueEndReason.FAILED ->
                    MinestomDialogueSpeechListener.StopReason.SESSION_FAILED
                else -> MinestomDialogueSpeechListener.StopReason.SESSION_ENDED
            }
        if (speechListener !== MinestomDialogueSpeechListener.NO_OP) {
            scheduler.execute { stopSpeech(speechStop, terminal = true) }
        }
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
