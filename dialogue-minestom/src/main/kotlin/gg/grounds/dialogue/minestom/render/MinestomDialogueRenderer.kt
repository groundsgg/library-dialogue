package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.internal.OwnedScope
import gg.grounds.dialogue.core.internal.PendingOperation
import gg.grounds.dialogue.core.internal.SerialCommandQueue
import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.port.OwnedResource
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueFailure
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.InputResult
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.minestom.MinestomConnectionScope
import gg.grounds.dialogue.minestom.input.ChatInputTokens
import gg.grounds.dialogue.minestom.input.DialogueInputSource
import gg.grounds.dialogue.minestom.input.SurfaceInputTargets
import gg.grounds.dialogue.minestom.input.TargetSnapshot
import gg.grounds.dialogue.presentation.ChoiceViewport
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueSurface
import gg.grounds.dialogue.presentation.UiLabelSource
import java.time.Duration
import java.util.Collections
import java.util.HashMap
import java.util.Optional
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Supplier
import net.minestom.server.entity.Player

/**
 * One presenter and one Core session sharing [scope] and [targets] with native surfaces and input.
 *
 * Copies [profiles] and checks every directly known native surface, under any key, against the
 * exact scope and registry. Explicit [tokens] must be the same issuer used by each chat surface.
 * With null tokens, output remains enabled and [resolveChat] returns empty. The short constructor
 * supplies null tokens and empty geometry. Borrowers wire opaque wrappers, [geometry], sinks,
 * speaker callbacks and input targets consistently.
 *
 * Requests must identify the scope player's UUID string and host generation. Frames must match
 * their prepared session. Ownership failures complete the operation exceptionally before output
 * state changes and never trigger fallback. Acknowledgement publishes input; close retires it and
 * waits for owned resources, including late acquisitions, through the supplied scheduler.
 * [DialogueSurface.FrameRejected] is a proven precommit failure: its attempted observers fail, the
 * prior physical ACK remains usable, and newer pending work keeps its original deadline. Coalesced
 * or stale views not attempted retain the existing acknowledgement contract. Unknown write
 * failures, partial writes and timeouts retain their existing fatal/fallback behavior. Browse
 * observers follow the selected page intent, not just its semantic view; superseded unattempted
 * pages can share the selected attempt's ACK and ACCEPTED/STALE outcome. Page staging and drawing
 * run in the same existing serialized attempt so pending requests cannot relabel an earlier draw.
 */
class MinestomDialogueRenderer(
    private val scope: MinestomConnectionScope,
    profiles: Map<String, DialoguePresenter.SurfaceProfile>,
    private val clock: DialogueScheduler,
    private val controls: DialoguePresenter.Controls,
    private val labels: UiLabelSource,
    private val targets: SurfaceInputTargets,
    private val status: Consumer<PresentationStatus>,
    private val tokens: ChatInputTokens?,
    private val geometry: Supplier<NpcDialogueSurface.Geometry?>,
) : DialogueRenderer, DialogueInputSource {
    private val profiles = Collections.unmodifiableMap(HashMap(profiles))
    @Volatile private var active: Run? = null
    private val refreshing = AtomicBoolean()

    constructor(
        scope: MinestomConnectionScope,
        profiles: Map<String, DialoguePresenter.SurfaceProfile>,
        clock: DialogueScheduler,
        controls: DialoguePresenter.Controls,
        labels: UiLabelSource,
        targets: SurfaceInputTargets,
        status: Consumer<PresentationStatus>,
    ) : this(scope, profiles, clock, controls, labels, targets, status, null, Supplier { null })

    init {
        require(this.profiles.containsKey("chat")) { "Chat fallback required" }
        tokens?.requireOwner(scope, targets)
        this.profiles.values.forEach { profile ->
            when (val surface = profile.surface) {
                is ChatDialogueSurface -> surface.requireOwner(scope, targets, tokens)
                is HudDialogueSurface -> surface.requireOwner(scope, targets)
                is NpcDialogueSurface -> surface.requireOwner(scope, targets)
            }
        }
    }

    override fun effectiveSurface(): String {
        val run = active
        return if (run == null || !run.firstAck) "" else run.effective.surfaceKey
    }

    override fun hiddenHotbar(): Boolean {
        val run = active
        return run != null &&
            run.firstAck &&
            run.effective.surfaceKey != "chat" &&
            run.effective.effects["hideHud"] == "true"
    }

    override fun targets(): TargetSnapshot? {
        val current = targets.current()
        val run = active
        if (current == null && run != null && run.firstAck && !run.closed.get()) refreshOutput()
        return current
    }

    override fun npcGeometry(): NpcDialogueSurface.Geometry? = geometry.get()

    override fun browse(displayed: TargetSnapshot, start: Int): CompletionStage<InputResult> =
        active?.browse(displayed, start) ?: CompletableFuture.completedFuture(InputResult.STALE)

    override fun refreshOutput() {
        val run = active
        if (run != null && refreshing.compareAndSet(false, true)) {
            run.queue.execute {
                val latest = run.latest
                if (!run.closed.get() && latest != null) {
                    run.replace(latest).whenComplete { _, _ -> refreshing.set(false) }
                } else {
                    refreshing.set(false)
                }
            }
        }
    }

    override fun resolveChat(
        player: Player,
        generation: Long,
        token: String,
    ): Optional<TargetSnapshot.Target> =
        tokens?.resolve(player, generation, token) ?: Optional.empty()

    override fun prepare(
        request: DialogueRenderer.PresentationRequest
    ): CompletionStage<DialogueRenderer.PresentationHandle> {
        if (
            request.audienceId != scope.player.uuid.toString() ||
                request.hostGeneration != scope.generation
        ) {
            return CompletableFuture.failedFuture(
                IllegalArgumentException("Native request owner mismatch")
            )
        }
        val run = Run(request)
        active = run
        run.begin()
        return run.ready.minimalCompletionStage()
    }

    private inner class Run(val request: DialogueRenderer.PresentationRequest) :
        DialogueRenderer.PresentationHandle {
        val queue = SerialCommandQueue(clock, ::fatal)
        private val resources = OwnedScope(queue, clock, request.budgets.cleanup)
        private val presenter: DialoguePresenter
        val closed = AtomicBoolean()
        private val surfaces = AtomicLong()
        val ready = CompletableFuture<DialogueRenderer.PresentationHandle>()

        private inner class BrowseIntent(val expected: DialogueView, val start: Int)

        private var pageIntent: BrowseIntent? = null

        private inner class WriteObserver(
            val result: CompletableFuture<Void>,
            var selected: DialogueView,
            var page: BrowseIntent?,
        ) {
            var pageAccepted = false
            // A physical ACK may precede settlement while a newer frame is coalescing.
            var acknowledged = false
        }

        private val waiters = ArrayList<WriteObserver>()
        private var acknowledgedView: DialogueView? = null
        private val configurations = ArrayList<CompletableFuture<Void>>()
        private val browsers = ArrayList<CompletableFuture<InputResult>>()
        private val waits = ArrayList<PendingOperation<*>>()
        private var closing: CompletionStage<Void>? = null
        private var delegate: DialogueRenderer.PresentationHandle? = null
        private var requested = request.preferences
        var effective: PresentationPreferences = request.preferences
            private set

        var latest: DialogueView? = null
            private set

        private var diagnostic = "PREPARING"
        private var changing = false
        private var inflight = false
        var firstAck = false
            private set

        private var intent = 0L
        private var writeEpoch = 0L
        private var frameIntent = 0L
        private var failure: Throwable? = null
        private var batch: CompletableFuture<Void>? = null
        private var batchDeadline = 0L

        init {
            val wrapped = HashMap<String, DialoguePresenter.SurfaceProfile>()
            profiles.forEach { (key, profile) ->
                wrapped[key] =
                    DialoguePresenter.SurfaceProfile(
                        { context ->
                            val generation = surfaces.incrementAndGet()
                            invalidate()
                            profile.surface.open(
                                DialogueSurface.SurfaceContext(
                                    context.sessionId,
                                    context.audienceId,
                                    context.hostGeneration,
                                    context.surfaceKey,
                                    context.profilePin,
                                    context.cancellation,
                                    generation,
                                    context.deadlineNanos,
                                    context.preferences,
                                    context.cleanupBudget,
                                )
                            )
                        },
                        profile.layout,
                        profile.theme,
                        profile.metrics,
                        profile.semanticOnly,
                        profile.layoutResolver,
                    )
            }
            presenter = DialoguePresenter(wrapped, clock, controls, labels)
        }

        private fun invalidate() {
            targets.current()?.let { targets.invalidateCurrent(it.surfaceGeneration()) }
        }

        private fun report() {
            status.accept(
                PresentationStatus(
                    requested.surfaceKey,
                    if (firstAck) effective.surfaceKey else "",
                    diagnostic,
                    surfaces.get(),
                )
            )
        }

        private fun chat(): PresentationPreferences =
            PresentationPreferences(
                "chat",
                effective.graphemeDelay,
                effective.reducedMotion,
                effective.effects,
            )

        fun begin() {
            queue.execute {
                resources.prepare {
                    CompletableFuture.completedFuture(
                        request.cancellation.onCancel { closeAsync() }
                    )
                }
                prepareAttempt(effective, false)
            }
        }

        private fun prepareAttempt(preferences: PresentationPreferences, fallback: Boolean) {
            if (closed.get()) return
            val work = resources.prepare {
                presenter.prepare(
                    DialogueRenderer.PresentationRequest(
                        request.sessionId,
                        request.audienceId,
                        request.hostGeneration,
                        preferences,
                        request.cancellation,
                        request.deadlineNanos,
                        request.budgets,
                    )
                )
            }
            await(work, request.deadlineNanos) { handle, error ->
                if (closed.get()) return@await
                if (error != null) {
                    val code = known(error)
                    if (!fallback && preferences.surfaceKey != "chat" && code != null) {
                        effective = chat()
                        diagnostic = code
                        prepareAttempt(effective, true)
                    } else {
                        failPreparation(error)
                    }
                } else {
                    delegate = handle
                    effective = preferences
                    ready.complete(this)
                    report()
                }
            }
        }

        private fun <T> await(
            stage: CompletionStage<T>,
            deadline: Long,
            callback: BiConsumer<T?, Throwable?>,
        ) {
            waits.removeIf(PendingOperation<*>::isDone)
            waits.add(
                PendingOperation(
                    queue,
                    clock,
                    Duration.ofNanos(maxOf(1, deadline - clock.nanoTime())),
                    stage,
                    callback,
                )
            )
        }

        private fun failPreparation(error: Throwable) {
            failure = error
            closeAsync().whenComplete { _, cleanup ->
                ready.completeExceptionally(
                    if (cleanup == null) error else OwnedResource.PreparationFailure(cleanup)
                )
            }
        }

        override fun replace(view: DialogueView): CompletionStage<Void> {
            if (view.sessionId() != request.sessionId) {
                return CompletableFuture.failedFuture(
                    DialogueSurface.FrameRejected("Native frame session mismatch")
                )
            }
            val result = CompletableFuture<Void>()
            queue.execute {
                if (closed.get()) {
                    result.completeExceptionally(CancellationException())
                    return@execute
                }
                admitWrite(view, result, pageIntent)
            }
            return result.minimalCompletionStage()
        }

        private fun admitWrite(
            view: DialogueView,
            result: CompletableFuture<Void>,
            page: BrowseIntent?,
        ): WriteObserver {
            val current = latest
            if (current == null || view.stateRevision() >= current.stateRevision()) latest = view
            frameIntent++
            val observer = WriteObserver(result, checkNotNull(latest), page)
            waiters.add(observer)
            flush()
            return observer
        }

        fun browse(displayed: TargetSnapshot?, start: Int): CompletionStage<InputResult> {
            val result = CompletableFuture<InputResult>()
            queue.execute {
                val currentDelegate = delegate
                val currentLatest = latest
                if (
                    closed.get() ||
                        displayed == null ||
                        changing ||
                        !firstAck ||
                        effective.surfaceKey != "npc" ||
                        displayed !== targets.current() ||
                        currentLatest == null ||
                        currentLatest != acknowledgedView ||
                        displayed.stateRevision() != currentLatest.stateRevision() ||
                        displayed.renderRevision() != currentLatest.renderRevision() ||
                        currentDelegate !is ChoiceViewport.Controller
                ) {
                    result.complete(InputResult.STALE)
                    return@execute
                }
                browsers.removeIf(CompletableFuture<InputResult>::isDone)
                browsers.add(result)
                val selected = pageIntent
                val page =
                    if (selected?.expected == currentLatest && selected.start == start) selected
                    else BrowseIntent(currentLatest, start)
                pageIntent = page
                val writing = CompletableFuture<Void>()
                val observer = admitWrite(currentLatest, writing, page)
                writing.whenComplete { _, error ->
                    if (error != null) result.completeExceptionally(error)
                    else
                        result.complete(
                            if (observer.pageAccepted) InputResult.ACCEPTED else InputResult.STALE
                        )
                }
            }
            return result.minimalCompletionStage()
        }

        private fun flush() {
            val currentDelegate = delegate
            if (
                closed.get() || changing || inflight || waiters.isEmpty() || currentDelegate == null
            ) {
                return
            }
            if (batch == null) {
                batch = CompletableFuture()
                batchDeadline =
                    if (firstAck) clock.nanoTime() + request.budgets.prepare.toNanos()
                    else request.deadlineNanos
                val currentBatch = requireNotNull(batch)
                await(currentBatch, batchDeadline) { _, error ->
                    if (error != null && batch === currentBatch) fatal(error)
                }
            }
            val attempted = waiters.filter { !it.acknowledged }
            if (attempted.isEmpty()) return
            inflight = true
            val epoch = ++writeEpoch
            val writingIntent = frameIntent
            val frame = checkNotNull(latest)
            val requestedPage = attempted.lastOrNull()?.page
            val page = requestedPage?.takeIf { it.expected == frame }
            if (page == null && pageIntent === requestedPage) pageIntent = null
            attempted.forEach {
                it.selected = frame
                it.page = page
            }
            // Staging has not called replace: its failure belongs only to this browse group.
            var stagingFailed = false
            val output =
                if (page == null || currentDelegate !is ChoiceViewport.Controller) {
                    attempted.forEach { it.pageAccepted = false }
                    call { currentDelegate.replace(frame) }
                } else {
                    val writing = CompletableFuture<Void>()
                    call { currentDelegate.setChoiceStart(frame, page.start) }
                        .whenComplete { changed, error ->
                            queue.execute {
                                if (closed.get() || epoch != writeEpoch) {
                                    writing.completeExceptionally(CancellationException())
                                } else if (error != null) {
                                    stagingFailed = true
                                    writing.completeExceptionally(error)
                                } else {
                                    attempted.forEach { it.pageAccepted = changed == true }
                                    call { currentDelegate.replace(frame) }
                                        .whenComplete { _, failure ->
                                            if (failure == null) writing.complete(null)
                                            else writing.completeExceptionally(failure)
                                        }
                                }
                            }
                        }
                    writing
                }
            output.whenComplete { _, error ->
                queue.execute {
                    if (closed.get() || epoch != writeEpoch) return@execute
                    inflight = false
                    if (error != null) {
                        if (stagingFailed || frameRejection(error) != null) {
                            val rejected = waiters.filter {
                                !it.acknowledged &&
                                    (it in attempted || (it.selected == frame && it.page === page))
                            }
                            waiters.removeAll(rejected.toSet())
                            rejected.forEach { it.result.completeExceptionally(error) }
                            if (pageIntent === page) pageIntent = null
                            latest =
                                waiters.lastOrNull { !it.acknowledged }?.selected
                                    ?: acknowledgedView
                            if (waiters.any { !it.acknowledged }) flush()
                            else completeAcknowledged()
                            return@execute
                        }
                        val code = known(error)
                        if (code != null && effective.surfaceKey != "chat") {
                            diagnostic = code
                            switchOutput(chat(), false, null, batchDeadline)
                        } else {
                            fatal(error)
                        }
                        return@execute
                    }
                    acknowledgedView = frame
                    attempted.forEach { it.acknowledged = true }
                    if (
                        pageIntent === page && waiters.none { !it.acknowledged && it.page === page }
                    )
                        pageIntent = null
                    if (frame !== latest || writingIntent != frameIntent) {
                        flush()
                        return@execute
                    }
                    completeAcknowledged()
                }
            }
        }

        private fun completeAcknowledged() {
            if (acknowledgedView != null) {
                firstAck = true
                if (diagnostic == "PREPARING") diagnostic = ""
                report()
            }
            val completed = waiters.toList()
            waiters.clear()
            val current = batch
            batch = null
            current?.complete(null)
            completed.forEach { it.result.complete(null) }
        }

        override fun reconfigure(preferences: PresentationPreferences): CompletionStage<Void> {
            val result = CompletableFuture<Void>()
            queue.execute {
                if (closed.get()) {
                    result.completeExceptionally(CancellationException())
                    return@execute
                }
                configurations.removeIf(CompletableFuture<Void>::isDone)
                configurations.add(result)
                requested = preferences
                diagnostic = ""
                switchOutput(
                    preferences,
                    true,
                    result,
                    clock.nanoTime() + request.budgets.prepare.toNanos(),
                )
            }
            return result.minimalCompletionStage()
        }

        private fun switchOutput(
            preferences: PresentationPreferences,
            user: Boolean,
            result: CompletableFuture<Void>?,
            deadline: Long,
        ) {
            changing = true
            inflight = false
            writeEpoch++
            val change = ++intent
            invalidate()
            val currentDelegate = delegate ?: return
            await(call { currentDelegate.reconfigure(preferences) }, deadline) { _, error ->
                if (closed.get()) return@await
                if (change != intent) {
                    result?.completeExceptionally(
                        DialogueRenderer.ReconfigurationRejected(
                            "SURFACE_SUPERSEDED",
                            CancellationException(),
                            effective,
                        )
                    )
                    return@await
                }
                if (error != null) {
                    val code = known(error)
                    if (code != null && preferences.surfaceKey != "chat") {
                        diagnostic = code
                        switchOutput(chat(), false, result, deadline)
                    } else {
                        changing = false
                        result?.completeExceptionally(error)
                        fatal(error)
                    }
                    return@await
                }
                effective = preferences
                changing = false
                if (latest != null) {
                    firstAck = true
                    report()
                }
                result?.complete(null)
                flush()
            }
        }

        private fun fatal(error: Throwable) {
            if (closed.get()) return
            failure = error
            invalidate()
            val pending = waiters.toList()
            waiters.clear()
            pending.forEach { it.result.completeExceptionally(error) }
            request.cancellation.cancel(DialogueEndReason.FAILED)
            closeAsync()
        }

        override fun closeAsync(): CompletionStage<Void> {
            val result: CompletableFuture<Void>
            synchronized(this) {
                closing?.let {
                    return it
                }
                result = CompletableFuture()
                closing = result.minimalCompletionStage()
                closed.set(true)
            }
            queue.execute {
                invalidate()
                waits.forEach(PendingOperation<*>::cancel)
                waits.clear()
                val error = CancellationException("Renderer closed")
                waiters.forEach { it.result.completeExceptionally(error) }
                waiters.clear()
                configurations.forEach { it.completeExceptionally(error) }
                configurations.clear()
                browsers.forEach { it.completeExceptionally(error) }
                browsers.clear()
                resources.closeAsync().whenComplete { _, cleanup ->
                    queue.execute {
                        if (active === this) active = null
                        delegate = null
                        latest = null
                        acknowledgedView = null
                        pageIntent = null
                        ready.completeExceptionally(
                            if (cleanup != null) OwnedResource.PreparationFailure(cleanup)
                            else failure ?: error
                        )
                        if (cleanup == null) result.complete(null)
                        else result.completeExceptionally(cleanup)
                    }
                }
            }
            return requireNotNull(closing)
        }
    }

    companion object {
        private val KNOWN =
            setOf(
                "MISSING_PACK",
                "MISSING_GLYPH",
                "LAYOUT_OVERFLOW",
                "FONT_PIN_MISMATCH",
                "UNSUPPORTED_RASTER",
                "OUTPUT_SUPPRESSED",
            )

        private fun frameRejection(error: Throwable): DialogueSurface.FrameRejected? {
            var current = error
            while (current is CompletionException || current is ExecutionException) {
                current = current.cause ?: return null
            }
            return current as? DialogueSurface.FrameRejected
        }

        private fun known(error: Throwable): String? {
            var current: Throwable? = error
            while (current != null) {
                if (
                    current is OwnedResource.PreparationFailure ||
                        (current is DialogueFailure &&
                            (current.code().contains("CLEANUP") ||
                                current.code().contains("TIMEOUT")))
                ) {
                    return null
                }
                current = current.cause
            }
            current = error
            while (current != null) {
                if (current is DialogueFailure && current.code() in KNOWN) return current.code()
                current = current.cause
            }
            return null
        }

        private fun <T> call(supplier: Supplier<CompletionStage<T>>): CompletionStage<T> =
            try {
                requireNotNull(supplier.get())
            } catch (error: Throwable) {
                CompletableFuture.failedFuture(error)
            }
    }
}
