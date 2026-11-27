package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.internal.OwnedScope
import gg.grounds.dialogue.core.internal.PendingOperation
import gg.grounds.dialogue.core.internal.SerialCommandQueue
import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.port.DialogueRenderer.ReconfigurationRejected
import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.port.OwnedResource
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueFailure
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.validation.ContentFingerprint
import gg.grounds.dialogue.presentation.internal.DialogueFrameComposer
import gg.grounds.dialogue.presentation.internal.GraphicalFrameInput
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function
import java.util.function.Supplier
import kotlin.math.max

/** Owns surface sessions and delegates frame composition without a second reveal clock or graph. */
class DialoguePresenter : DialogueRenderer {
    @JvmRecord
    data class SurfaceProfile(
        val surface: DialogueSurface,
        val layout: LayoutProfile?,
        val theme: DialogueTheme?,
        val metrics: FontMetrics?,
        val semanticOnly: Boolean,
        val layoutResolver: Function<PresentationPreferences, LayoutProfile>?,
    ) {
        constructor(
            surface: DialogueSurface,
            layout: LayoutProfile?,
            theme: DialogueTheme?,
            metrics: FontMetrics?,
            semanticOnly: Boolean,
        ) : this(surface, layout, theme, metrics, semanticOnly, null)

        init {
            if (!semanticOnly) {
                requireNotNull(layout)
                requireNotNull(theme)
                requireNotNull(metrics)
            }
        }

        fun resolveLayout(preferences: PresentationPreferences): LayoutProfile =
            layoutResolver?.apply(preferences) ?: requireNotNull(layout)

        companion object {
            @JvmStatic
            fun semantic(surface: DialogueSurface): SurfaceProfile =
                SurfaceProfile(surface, null, null, null, true)
        }
    }

    enum class ConfirmAction {
        ATTACK,
        USE,
        SWAP_HANDS,
    }

    @JvmRecord
    data class Controls(val confirm: ConfirmAction, val scroll: Boolean) {
        companion object {
            @JvmStatic fun defaults(): Controls = Controls(ConfirmAction.SWAP_HANDS, true)
        }
    }

    class FallbackRequired(code: String) : DialogueFailure(code, code)

    private val profiles: Map<String, SurfaceProfile>
    private val clock: DialogueScheduler
    private val controls: Controls
    private val labels: UiLabelSource

    constructor(
        profiles: Map<String, SurfaceProfile>,
        scheduler: DialogueScheduler,
    ) : this(profiles, scheduler, Controls.defaults())

    constructor(
        profiles: Map<String, SurfaceProfile>,
        scheduler: DialogueScheduler,
        controls: Controls,
    ) : this(profiles, scheduler, controls, UiLabelSource.defaults())

    constructor(
        profiles: Map<String, SurfaceProfile>,
        scheduler: DialogueScheduler,
        controls: Controls,
        labels: UiLabelSource,
    ) {
        this.profiles = java.util.Map.copyOf(profiles)
        clock = scheduler
        this.controls = controls
        this.labels = labels
    }

    private fun profile(key: String): SurfaceProfile =
        profiles[key] ?: throw FallbackRequired("MISSING_SURFACE")

    fun compose(
        view: DialogueView,
        key: String,
        preferences: PresentationPreferences,
    ): WidgetFrame = compose(view, key, preferences, null)

    fun compose(
        view: DialogueView,
        key: String,
        preferences: PresentationPreferences,
        choiceStart: Int?,
    ): WidgetFrame {
        val profile = profile(key)
        if (profile.semanticOnly) return WidgetFrame(view, emptyList(), "semantic", "semantic")
        val layout = profile.resolveLayout(preferences)
        val metrics = requireNotNull(profile.metrics)
        val reasons = linkedMapOf<String, String>()
        view
            .choices()
            .filter { it.availability != DialogueView.Availability.AVAILABLE }
            .forEach { reasons[it.id] = checkNotNull(it.unavailableReason) }
        val ui =
            requireNotNull(
                labels.resolve(
                    UiLabelSource.LabelRequest(
                        view.localeTag(),
                        view.hint(),
                        controls,
                        view.notice(),
                        reasons,
                        metrics.supportsControlGlyphs(),
                    )
                )
            )
        val theme = requireNotNull(profile.theme)
        return DialogueFrameComposer.compose(
            GraphicalFrameInput(
                view,
                layout,
                theme,
                metrics,
                ui,
                if (preferences.reducedMotion()) Duration.ZERO else theme.transition(),
                choiceStart,
            )
        )
    }

    /**
     * Computes the same canonical profile pin for surface preparation regardless of layout style.
     */
    private fun profilePin(
        profile: SurfaceProfile,
        preferences: PresentationPreferences,
    ): String {
        if (profile.semanticOnly) return "semantic"
        val layoutProfile = profile.resolveLayout(preferences)
        val theme = requireNotNull(profile.theme)
        val layout = linkedMapOf<String, Any>()
        layout["key"] = layoutProfile.key
        if (layoutProfile.style != LayoutProfile.Style.PANELS)
            layout["style"] = layoutProfile.style.name
        layout["width"] = layoutProfile.width
        layout["choiceWidth"] = layoutProfile.choiceWidth
        layout["padding"] = layoutProfile.padding
        if (layoutProfile.choicePadding != layoutProfile.padding)
            layout["choicePadding"] = layoutProfile.choicePadding
        layout["maxTextLines"] = layoutProfile.maxTextLines
        layout["choiceCapacity"] = layoutProfile.choiceCapacity
        layout["maxChoiceLines"] = layoutProfile.maxChoiceLines
        layout["placement"] = layoutProfile.placement.name.lowercase(Locale.ROOT)
        layout["gap"] = layoutProfile.gap
        if (layoutProfile.columnGap != layoutProfile.gap)
            layout["columnGap"] = layoutProfile.columnGap
        if (layoutProfile.navigationButtons) layout["navigationButtons"] = true
        return ContentFingerprint.hash(
                ContentFingerprint.canonicalBytes(
                    mapOf(
                        "layout" to layout,
                        "theme" to
                            mapOf(
                                "id" to theme.id(),
                                "colours" to theme.colours(),
                                "selectedScale" to theme.selectedScale(),
                                "transitionNanos" to theme.transition().toNanos(),
                            ),
                        "font" to requireNotNull(profile.metrics).pin(),
                    )
                )
            )
            .sha256
    }

    override fun prepare(
        request: DialogueRenderer.PresentationRequest
    ): CompletionStage<DialogueRenderer.PresentationHandle> {
        val session = SurfaceSession(request)
        session.begin()
        return session.ready.minimalCompletionStage()
    }

    private inner class SurfaceSession(private val request: DialogueRenderer.PresentationRequest) :
        DialogueRenderer.PresentationHandle, ChoiceViewport.Controller {
        val choiceCursor = ChoiceViewport.Cursor()
        val queue: SerialCommandQueue
        val scope: OwnedScope
        val ready = CompletableFuture<DialogueRenderer.PresentationHandle>()
        val closed = AtomicBoolean()
        val outstanding = ArrayList<CompletableFuture<Void>>()
        val waits = ArrayList<PendingOperation<*>>()
        var closeResult: CompletableFuture<Void>? = null
        var closeView: CompletionStage<Void>? = null
        var preparationFailure: Throwable? = null
        var preferences = request.preferences
        var surface: DialogueSurface.SurfaceHandle? = null
        var surfaceKey = preferences.surfaceKey()
        var lastView: DialogueView? = null
        var generation = 0L
        var outputGeneration = 0L
        var lastWritten = -1L
        var pending: PendingFrame? = null
        var inflight: PendingFrame? = null
        var changing: Switch? = null

        init {
            queue =
                SerialCommandQueue(clock) { error ->
                    preparationFailure = error
                    closeAsync()
                }
            scope = OwnedScope(queue, clock, request.budgets.cleanup)
        }

        inner class PendingFrame(val view: DialogueView) {
            val waiters = ArrayList<CompletableFuture<Void>>()
        }

        inner class Switch(
            val gen: Long,
            val deadline: Long,
            val next: PresentationPreferences,
            val result: CompletableFuture<Void>,
        ) {
            val work = CompletableFuture<Void>()
            var candidate: DialogueSurface.SurfaceHandle? = null
            var committed = false
        }

        fun composeCurrent(
            view: DialogueView,
            key: String,
            prefs: PresentationPreferences,
        ): WidgetFrame {
            if (profile(key).semanticOnly) return compose(view, key, prefs)
            val currentLayout = profile(key).resolveLayout(prefs)
            return compose(
                view,
                key,
                prefs,
                if (currentLayout.navigationButtons)
                    choiceCursor.startFor(view, currentLayout.choiceCapacity)
                else null,
            )
        }

        override fun setChoiceStart(expected: DialogueView, start: Int): CompletionStage<Boolean> {
            val result = CompletableFuture<Boolean>()
            queue.execute {
                try {
                    val current = lastView
                    if (
                        closed.get() ||
                            current == null ||
                            current != expected ||
                            profile(surfaceKey).semanticOnly
                    ) {
                        result.complete(false)
                        return@execute
                    }
                    val currentLayout = profile(surfaceKey).resolveLayout(preferences)
                    result.complete(
                        currentLayout.navigationButtons &&
                            choiceCursor.browse(current, currentLayout.choiceCapacity, start)
                    )
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
            return result.minimalCompletionStage()
        }

        fun valid(operation: Switch): Boolean =
            !closed.get() &&
                operation === changing &&
                operation.gen == generation &&
                !operation.result.isDone &&
                clock.nanoTime() < operation.deadline

        fun begin() {
            queue.execute {
                if (request.cancellation.isCancelled()) {
                    closeAsync()
                    return@execute
                }
                scope.prepare {
                    CompletableFuture.completedFuture(
                        request.cancellation.onCancel { closeAsync() }
                    )
                }
                if (closed.get()) return@execute
                val opened = open(surfaceKey, ++generation, request.deadlineNanos, preferences)
                waits +=
                    PendingOperation(
                        queue,
                        clock,
                        remaining(request.deadlineNanos),
                        opened,
                    ) { value, error ->
                        if (closed.get()) return@PendingOperation
                        if (error != null) {
                            preparationFailure = error
                            closeAsync()
                        } else {
                            surface = value
                            ready.complete(this)
                        }
                    }
            }
        }

        fun remaining(deadline: Long): Duration =
            Duration.ofNanos(max(1L, deadline - clock.nanoTime()))

        fun open(
            key: String,
            gen: Long,
            deadline: Long,
            openingPreferences: PresentationPreferences,
        ): CompletionStage<DialogueSurface.SurfaceHandle> = scope.prepare {
            val selected = profile(key)
            selected.surface.open(
                DialogueSurface.SurfaceContext(
                    request.sessionId,
                    request.audienceId,
                    request.hostGeneration,
                    key,
                    profilePin(selected, openingPreferences),
                    request.cancellation,
                    gen,
                    deadline,
                    openingPreferences,
                    request.budgets.cleanup,
                )
            )
        }

        fun track(result: CompletableFuture<Void>) {
            outstanding.removeIf(CompletableFuture<Void>::isDone)
            outstanding += result
        }

        override fun replace(view: DialogueView): CompletionStage<Void> {
            val result = CompletableFuture<Void>()
            queue.execute {
                if (closed.get()) {
                    result.completeExceptionally(CancellationException("Surface closed"))
                    return@execute
                }
                if (request.sessionId != view.sessionId()) {
                    result.completeExceptionally(IllegalArgumentException("Foreign session view"))
                    return@execute
                }
                track(result)
                val previous = lastView
                if (previous != null && view.stateRevision() < previous.stateRevision()) {
                    when {
                        lastWritten >= view.stateRevision() -> result.complete(null)
                        pending != null -> pending!!.waiters += result
                        inflight != null -> inflight!!.waiters += result
                        else -> enqueue(previous, listOf(result))
                    }
                    return@execute
                }
                lastView = view
                enqueue(view, listOf(result))
            }
            return result.minimalCompletionStage()
        }

        fun enqueue(view: DialogueView, waiters: List<CompletableFuture<Void>>) {
            val frame = PendingFrame(view)
            pending?.let { frame.waiters.addAll(it.waiters) }
            frame.waiters.addAll(waiters)
            pending = frame
            flush()
        }

        fun finishFrame(frame: PendingFrame, error: Throwable?) {
            frame.waiters.forEach {
                if (error == null) it.complete(null) else it.completeExceptionally(error)
            }
            frame.waiters.clear()
        }

        fun flush() {
            val target = surface ?: return
            val frame = pending ?: return
            if (closed.get() || inflight != null) return
            pending = null
            val widgets =
                try {
                    composeCurrent(frame.view, surfaceKey, preferences)
                } catch (error: Throwable) {
                    finishFrame(frame, error)
                    flush()
                    return
                }
            inflight = frame
            val gen = outputGeneration
            call { target.replace(widgets) }
                .whenComplete { _, error ->
                    queue.execute {
                        if (gen != outputGeneration) return@execute
                        if (error == null)
                            lastWritten = max(lastWritten, frame.view.stateRevision())
                        finishFrame(frame, error)
                        inflight = null
                        flush()
                    }
                }
        }

        fun releaseCandidate(value: DialogueSurface.SurfaceHandle?) {
            if (value == null) return
            scope.release(value).whenComplete { _, error ->
                queue.execute {
                    if (error != null) {
                        preparationFailure = error
                        request.cancellation.cancel(DialogueEndReason.FAILED)
                        closeAsync()
                    }
                }
            }
        }

        fun reject(operation: Switch, code: String, error: Throwable) {
            releaseCandidate(operation.candidate)
            operation.work.completeExceptionally(ReconfigurationRejected(code, error, preferences))
        }

        override fun reconfigure(next: PresentationPreferences): CompletionStage<Void> {
            val result = CompletableFuture<Void>()
            val deadline = clock.nanoTime() + request.budgets.prepare.toNanos()
            queue.execute {
                if (closed.get()) {
                    result.completeExceptionally(CancellationException("Surface closed"))
                    return@execute
                }
                track(result)
                generation++
                changing?.let { previous ->
                    previous.result.completeExceptionally(
                        ReconfigurationRejected(
                            "SURFACE_SUPERSEDED",
                            CancellationException("Superseded surface"),
                            preferences,
                        )
                    )
                    releaseCandidate(if (previous.committed) null else previous.candidate)
                    previous.work.completeExceptionally(CancellationException("Superseded surface"))
                    changing = null
                }
                if (next.surfaceKey() == surfaceKey) {
                    val configureGeneration = generation
                    call { requireNotNull(surface).configure(next) }
                        .whenComplete { _, error ->
                            queue.execute {
                                if (closed.get() || configureGeneration != generation) {
                                    result.completeExceptionally(
                                        ReconfigurationRejected(
                                            "SURFACE_SUPERSEDED",
                                            CancellationException(),
                                            preferences,
                                        )
                                    )
                                } else if (error != null) result.completeExceptionally(error)
                                else {
                                    preferences = next
                                    result.complete(null)
                                }
                            }
                        }
                    return@execute
                }
                val operation = Switch(generation, deadline, next, result)
                changing = operation
                waits.removeIf(PendingOperation<*>::isDone)
                waits +=
                    PendingOperation(queue, clock, remaining(deadline), operation.work) { _, error
                        ->
                        if (operation !== changing || closed.get()) return@PendingOperation
                        changing = null
                        if (error != null) {
                            if (!operation.committed) releaseCandidate(operation.candidate)
                            var cause = OwnedScope.unwrap(error)
                            if (!operation.committed && cause !is ReconfigurationRejected) {
                                cause =
                                    ReconfigurationRejected(
                                        "SURFACE_PREPARE_FAILED",
                                        cause,
                                        preferences,
                                    )
                            }
                            result.completeExceptionally(cause)
                        } else result.complete(null)
                    }
                open(next.surfaceKey(), operation.gen, deadline, next).whenComplete { value, error
                    ->
                    queue.execute { continueSwitch(operation, value, error) }
                }
            }
            return result.minimalCompletionStage()
        }

        private fun continueSwitch(
            operation: Switch,
            value: DialogueSurface.SurfaceHandle?,
            error: Throwable?,
        ) {
            operation.candidate = value
            if (!valid(operation)) {
                releaseCandidate(value)
                operation.work.completeExceptionally(
                    ReconfigurationRejected(
                        "SURFACE_EXPIRED",
                        CancellationException("Surface request expired"),
                        preferences,
                    )
                )
                return
            }
            if (error != null) {
                reject(operation, "SURFACE_PREPARE_FAILED", error)
                return
            }
            val candidate = requireNotNull(value)
            val frame =
                try {
                    lastView?.let {
                        composeCurrent(it, operation.next.surfaceKey(), operation.next)
                    }
                } catch (failure: Throwable) {
                    reject(operation, "SURFACE_LAYOUT_REJECTED", failure)
                    return
                }
            val first =
                frame?.let { call { candidate.replace(it) } }
                    ?: CompletableFuture.completedFuture(null)
            first.whenComplete { _, drawError ->
                queue.execute { commitSwitch(operation, candidate, frame, drawError) }
            }
        }

        private fun commitSwitch(
            operation: Switch,
            value: DialogueSurface.SurfaceHandle,
            frame: WidgetFrame?,
            drawError: Throwable?,
        ) {
            if (!valid(operation)) {
                releaseCandidate(value)
                operation.work.completeExceptionally(
                    ReconfigurationRejected(
                        "SURFACE_EXPIRED",
                        CancellationException("Surface request expired"),
                        preferences,
                    )
                )
                return
            }
            if (drawError != null) {
                reject(operation, "SURFACE_DRAW_REJECTED", drawError)
                return
            }
            val old = surface
            surface = value
            surfaceKey = operation.next.surfaceKey()
            preferences = operation.next
            operation.committed = true
            outputGeneration++
            lastWritten = frame?.semantic()?.stateRevision() ?: -1
            val transferred = ArrayList<CompletableFuture<Void>>()
            inflight?.let {
                transferred.addAll(it.waiters)
                it.waiters.clear()
                inflight = null
            }
            lastView?.let { enqueue(it, transferred) }
            scope.release(checkNotNull(old)).whenComplete { _, cleanup ->
                queue.execute {
                    if (cleanup != null) {
                        preparationFailure = cleanup
                        operation.work.completeExceptionally(
                            DialogueFailure("SURFACE_CLEANUP_FAILED", cleanup)
                        )
                        request.cancellation.cancel(DialogueEndReason.FAILED)
                        closeAsync()
                    } else operation.work.complete(null)
                }
            }
        }

        override fun closeAsync(): CompletionStage<Void> {
            synchronized(this) {
                closeView?.let {
                    return it
                }
                closeResult = CompletableFuture()
                closeView = closeResult!!.minimalCompletionStage()
                closed.set(true)
            }
            queue.execute {
                generation++
                waits.forEach { it.cancel() }
                waits.clear()
                val error = CancellationException("Surface closed")
                outstanding.forEach { it.completeExceptionally(error) }
                outstanding.clear()
                pending?.let { finishFrame(it, error) }
                pending = null
                inflight?.let { finishFrame(it, error) }
                inflight = null
                changing = null
                scope.closeAsync().whenComplete { _, cleanup ->
                    queue.execute {
                        surface = null
                        lastView = null
                        val failure =
                            if (cleanup == null) preparationFailure
                            else OwnedResource.PreparationFailure(cleanup)
                        ready.completeExceptionally(failure ?: error)
                        if (cleanup == null) closeResult!!.complete(null)
                        else closeResult!!.completeExceptionally(cleanup)
                    }
                }
            }
            return requireNotNull(closeView)
        }
    }

    companion object {
        private fun <T> call(supplier: Supplier<CompletionStage<T>>): CompletionStage<T> =
            try {
                requireNotNull(supplier.get())
            } catch (error: Throwable) {
                CompletableFuture.failedFuture(error)
            }
    }
}
