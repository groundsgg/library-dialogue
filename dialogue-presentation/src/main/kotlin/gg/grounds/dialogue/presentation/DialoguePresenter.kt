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
import gg.grounds.dialogue.core.text.Graphemes
import gg.grounds.dialogue.core.text.ResolvedText
import gg.grounds.dialogue.core.validation.ContentFingerprint
import gg.grounds.dialogue.presentation.WidgetFrame.Kind
import gg.grounds.dialogue.presentation.WidgetFrame.Rect
import gg.grounds.dialogue.presentation.WidgetFrame.Widget
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function
import java.util.function.Supplier
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Shared widget composition; does not own a second reveal clock or graph. */
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
        val widgets = ArrayList<Widget>()
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
        if (layout.style != LayoutProfile.Style.PANELS) {
            return composePrototypeHud(view, profile, layout, preferences, ui)
        }
        val text = ResolvedText(view.text(), view.localeTag())
        val body = layout(text, metrics, layout.width - 2 * layout.padding, layout.maxTextLines)
        val bodyHeight = body.height() + 2 * layout.padding
        widgets +=
            widget(
                "text",
                Kind.TEXT,
                Rect(0.0, 0.0, layout.width, bodyHeight),
                text,
                body,
                layout.padding,
                layout.padding,
                view.revealedGraphemes(),
                false,
                true,
                null,
                profile,
                preferences,
            )
        val name = ResolvedText(view.speaker(), view.localeTag())
        val nameLayout = layout(name, metrics, layout.width - 2 * layout.padding, 1)
        val nameWidth = max(nameLayout.width() + 2 * layout.padding, 40.0)
        val nameHeight = metrics.lineHeight() + layout.padding
        val nameX = (layout.width - nameWidth) / 2
        val nameY = -nameHeight - layout.gap
        widgets +=
            widget(
                "speaker",
                Kind.SPEAKER,
                Rect(nameX, nameY, nameWidth, nameHeight),
                name,
                nameLayout,
                (nameWidth - nameLayout.width()) / 2,
                (nameHeight - nameLayout.height()) / 2,
                Graphemes.split(name.plain).size,
                false,
                true,
                null,
                profile,
                preferences,
            )
        val window =
            ChoiceViewport()
                .window(view.choices(), view.selectedChoiceId(), layout.choiceCapacity, choiceStart)
        val choiceLabels =
            window.choices().map { choice ->
                val reason =
                    if (choice.availability == DialogueView.Availability.AVAILABLE) ""
                    else ui.choiceReasons().getOrDefault(choice.id, "")
                ResolvedText(
                    choice.label + if (reason.isEmpty()) "" else "\n$reason",
                    view.localeTag(),
                )
            }
        val choiceLayouts = choiceLabels.map {
            layout(
                it,
                metrics,
                layout.choiceWidth - 2 * layout.choicePadding,
                layout.maxChoiceLines,
            )
        }
        var maxLabelHeight = choiceLayouts.maxOfOrNull { it.height() } ?: metrics.lineHeight()
        if (layout.navigationButtons) {
            for (choice in view.choices()) {
                val reason =
                    if (choice.availability == DialogueView.Availability.AVAILABLE) ""
                    else ui.choiceReasons().getOrDefault(choice.id, "")
                val label =
                    ResolvedText(
                        choice.label + if (reason.isEmpty()) "" else "\n$reason",
                        view.localeTag(),
                    )
                maxLabelHeight =
                    max(
                        maxLabelHeight,
                        layout(
                                label,
                                metrics,
                                layout.choiceWidth - 2 * layout.choicePadding,
                                layout.maxChoiceLines,
                            )
                            .height(),
                    )
            }
        }
        val cardHeight = maxLabelHeight + 2 * layout.choicePadding
        val growth =
            ceil(layout.choiceWidth * (requireNotNull(profile.theme).selectedScale() - 1) / 2)
        val columnGap = layout.columnGap + growth
        val x =
            when (layout.placement) {
                LayoutProfile.ChoicePlacement.BELOW -> (layout.width - layout.choiceWidth) / 2
                LayoutProfile.ChoicePlacement.RIGHT -> layout.width + columnGap
                LayoutProfile.ChoicePlacement.LEFT -> -layout.choiceWidth - columnGap
            }
        val y =
            if (layout.placement != LayoutProfile.ChoicePlacement.BELOW) 0.0
            else bodyHeight + layout.gap
        window.choices().forEachIndexed { index, choice ->
            val bounds =
                Rect(x, y + index * (cardHeight + layout.gap), layout.choiceWidth, cardHeight)
            widgets +=
                widget(
                    "choice:${choice.id}",
                    Kind.CHOICE,
                    bounds,
                    choiceLabels[index],
                    choiceLayouts[index],
                    layout.choicePadding,
                    (cardHeight - choiceLayouts[index].height()) / 2,
                    Graphemes.split(choiceLabels[index].plain).size,
                    choice.id == view.selectedChoiceId(),
                    choice.availability == DialogueView.Availability.AVAILABLE,
                    choice.id,
                    profile,
                    preferences,
                )
        }
        val hintText = ResolvedText(ui.hint(), view.localeTag())
        val hintWidth =
            if (layout.placement != LayoutProfile.ChoicePlacement.BELOW) layout.choiceWidth
            else layout.width
        val hintLayout = layout(hintText, metrics, hintWidth, 2)
        val hintX = if (layout.placement != LayoutProfile.ChoicePlacement.BELOW) x else 0.0
        val hintY =
            if (layout.placement != LayoutProfile.ChoicePlacement.BELOW) {
                -hintLayout.height() -
                    layout.gap -
                    if (layout.navigationButtons && window.total() > layout.choiceCapacity) 28
                    else 0
            } else {
                y + window.choices().size * (cardHeight + layout.gap) + layout.gap
            }
        widgets +=
            widget(
                "hint",
                Kind.HINT,
                Rect(hintX, hintY, hintWidth, hintLayout.height()),
                hintText,
                hintLayout,
                0.0,
                0.0,
                Graphemes.split(ui.hint()).size,
                false,
                true,
                null,
                profile,
                preferences,
            )
        if (ui.notice().isNotEmpty()) {
            val statusText = ResolvedText(ui.notice(), view.localeTag())
            val statusLayout = layout(statusText, metrics, hintWidth, 2)
            val statusY =
                if (layout.placement != LayoutProfile.ChoicePlacement.BELOW) {
                    y + window.choices().size * (cardHeight + layout.gap) + layout.gap
                } else hintY + hintLayout.height() + layout.gap
            widgets +=
                widget(
                    "status",
                    Kind.HINT,
                    Rect(hintX, statusY, hintWidth, statusLayout.height()),
                    statusText,
                    statusLayout,
                    0.0,
                    0.0,
                    Graphemes.split(statusText.plain).size,
                    false,
                    true,
                    null,
                    profile,
                    preferences,
                )
        }
        var scroll =
            if (window.total() > layout.choiceCapacity) {
                scrollbar(
                    window,
                    Rect(
                        x + layout.choiceWidth + growth + 8,
                        y,
                        2.0,
                        layout.choiceCapacity * cardHeight +
                            (layout.choiceCapacity - 1) * layout.gap,
                    ),
                )
            } else null
        if (scroll != null && layout.navigationButtons) {
            val track = scroll.track()
            val buttons =
                listOf(
                    WidgetFrame.NavigationButton.page(
                        WidgetFrame.NavigationDirection.PREVIOUS,
                        Rect(x + (layout.choiceWidth - 64) / 2, track.y - 28, 64.0, 24.0),
                        if (window.start() > 0) window.start() - layout.choiceCapacity else null,
                    ),
                    WidgetFrame.NavigationButton.page(
                        WidgetFrame.NavigationDirection.NEXT,
                        Rect(
                            x + (layout.choiceWidth - 64) / 2,
                            track.y + track.height + 4,
                            64.0,
                            24.0,
                        ),
                        if (window.start() + layout.choiceCapacity < window.total())
                            window.start() + layout.choiceCapacity
                        else null,
                    ),
                )
            scroll = WidgetFrame.Scrollbar(track, scroll.thumb(), buttons)
        }
        return WidgetFrame(
            view,
            widgets,
            metrics.pin(),
            requireNotNull(profile.theme),
            null,
            scroll,
        )
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

        private fun composePrototypeHud(
            view: DialogueView,
            profile: SurfaceProfile,
            layout: LayoutProfile,
            preferences: PresentationPreferences,
            ui: UiLabelSource.UiLabels,
        ): WidgetFrame {
            val metrics = requireNotNull(profile.metrics)
            if (
                metrics.lineHeight() != 10.0 ||
                    layout.choiceCapacity != 3 ||
                    layout.maxTextLines > 3 ||
                    layout.maxChoiceLines > 2
            )
                throw FallbackRequired("UNSUPPORTED_RASTER")
            val widgets = ArrayList<Widget>()
            val body = ResolvedText(view.text(), view.localeTag())
            val bodyLayout = layout(body, metrics, layout.width - 18, 3)
            widgets +=
                widget(
                    "text",
                    Kind.TEXT,
                    Rect(0.0, 0.0, layout.width, 48.0),
                    body,
                    bodyLayout,
                    9.0,
                    17.0,
                    view.revealedGraphemes(),
                    false,
                    true,
                    null,
                    profile,
                    preferences,
                )
            val name = ResolvedText(view.speaker(), view.localeTag())
            val nameLayout = layout(name, metrics, layout.width - 18, 1)
            widgets +=
                widget(
                    "speaker",
                    Kind.SPEAKER,
                    Rect(9.0, 7.0, layout.width - 18, 10.0),
                    name,
                    nameLayout,
                    0.0,
                    0.0,
                    Graphemes.split(name.plain).size,
                    false,
                    true,
                    null,
                    profile,
                    preferences,
                )
            val window = ChoiceViewport().window(view.choices(), view.selectedChoiceId(), 3)
            val choiceLabels = ArrayList<ResolvedText>()
            val lines = ArrayList<TextLayout.TextLayoutResult>()
            for (choice in window.choices()) {
                val reason =
                    if (choice.availability == DialogueView.Availability.AVAILABLE) ""
                    else ui.choiceReasons().getOrDefault(choice.id, "")
                val prefix = if (choice.id == view.selectedChoiceId()) "> " else "  "
                val text =
                    ResolvedText(
                        prefix + choice.label + if (reason.isEmpty()) "" else "\n  $reason",
                        view.localeTag(),
                    )
                choiceLabels += text
                lines += layout(text, metrics, layout.choiceWidth, 2)
            }
            var rowHeight = lines.maxOfOrNull { it.height() } ?: 10.0
            val below = layout.placement == LayoutProfile.ChoicePlacement.BELOW
            val shared = layout.style == LayoutProfile.Style.HUD
            val cards = layout.style == LayoutProfile.Style.HUD_CARDS
            val count = window.choices().size
            if (cards) {
                for (choice in view.choices()) {
                    val reason =
                        if (choice.availability == DialogueView.Availability.AVAILABLE) ""
                        else ui.choiceReasons().getOrDefault(choice.id, "")
                    val label =
                        ResolvedText(
                            "> " + choice.label + if (reason.isEmpty()) "" else "\n  $reason",
                            view.localeTag(),
                        )
                    rowHeight =
                        max(rowHeight, layout(label, metrics, layout.choiceWidth, 2).height())
                }
            }
            val gap =
                if (cards && layout.placement == LayoutProfile.ChoicePlacement.LEFT) 15.0
                else layout.gap
            val columnWidth = layout.choiceWidth + 18
            val left =
                if (layout.placement == LayoutProfile.ChoicePlacement.LEFT) -columnWidth - gap
                else 0.0
            var width = if (below) layout.width else layout.width + gap + columnWidth
            if (cards && layout.placement != LayoutProfile.ChoicePlacement.LEFT) width += 9
            val step = if (cards) rowHeight + 13 else rowHeight
            val answerHeight = if (cards) 3 * (rowHeight + 10) + 6 else 3 * rowHeight + 14
            val height =
                if (shared) {
                    if (below) 84 + 3 * (rowHeight - 10) else if (rowHeight == 10.0) 51.0 else 75.0
                } else if (below) 54 + answerHeight else max(48.0, answerHeight)
            val x =
                when (layout.placement) {
                    LayoutProfile.ChoicePlacement.BELOW -> 9.0
                    LayoutProfile.ChoicePlacement.RIGHT -> layout.width + gap + 9
                    LayoutProfile.ChoicePlacement.LEFT -> left + 9
                }
            val y = if (below) if (shared) 50.0 else 61.0 else 7.0
            window.choices().forEachIndexed { index, choice ->
                val text = choiceLabels[index]
                val bounds =
                    if (cards) {
                        Rect(x - 9, y - 7 + index * step, layout.choiceWidth + 18, rowHeight + 10)
                    } else Rect(x, y + index * step, layout.choiceWidth, rowHeight)
                widgets +=
                    widget(
                        "choice:${choice.id}",
                        Kind.CHOICE,
                        bounds,
                        text,
                        lines[index],
                        if (cards) 9.0 else 0.0,
                        if (cards) centeredInkInset(lines[index], metrics, bounds.height) else 0.0,
                        Graphemes.split(text.plain).size,
                        choice.id == view.selectedChoiceId(),
                        choice.availability == DialogueView.Availability.AVAILABLE,
                        choice.id,
                        profile,
                        preferences,
                    )
            }
            val hint = ResolvedText(ui.hint(), view.localeTag())
            val hintLayout = layout(hint, metrics, width - 18, 2)
            widgets +=
                widget(
                    "hint",
                    Kind.HINT,
                    Rect(left + 9, height - 1, width - 18, hintLayout.height()),
                    hint,
                    hintLayout,
                    0.0,
                    0.0,
                    Graphemes.split(hint.plain).size,
                    false,
                    true,
                    null,
                    profile,
                    preferences,
                )
            if (ui.notice().isNotEmpty()) {
                val notice = ResolvedText(ui.notice(), view.localeTag())
                val noticeLayout = layout(notice, metrics, width - 18, 2)
                widgets +=
                    widget(
                        "status",
                        Kind.HINT,
                        Rect(
                            left + 9,
                            height - 1 + hintLayout.height() + 4,
                            width - 18,
                            noticeLayout.height(),
                        ),
                        notice,
                        noticeLayout,
                        0.0,
                        0.0,
                        Graphemes.split(notice.plain).size,
                        false,
                        true,
                        null,
                        profile,
                        preferences,
                    )
            }
            val chrome =
                WidgetFrame.Chrome(
                    (if (shared) "hud_" else if (cards) "hud_cards_" else "hud_split_") +
                        layout.placement.name.lowercase(Locale.ROOT) +
                        "_" +
                        rowHeight.toInt() +
                        "_" +
                        if (cards) count else 3,
                    Rect(left, 0.0, width, height),
                    height + 10,
                )
            val scroll =
                if (window.total() > layout.choiceCapacity) {
                    val trackHeight = if (cards) answerHeight else 3 * rowHeight
                    val top = if (cards) y - 7 else y
                    scrollbar(
                        window,
                        Rect(x + layout.choiceWidth + if (cards) 14 else 3, top, 2.0, trackHeight),
                    )
                } else null
            return WidgetFrame(
                view,
                widgets,
                metrics.pin(),
                requireNotNull(profile.theme),
                chrome,
                scroll,
            )
        }

        private fun centeredInkInset(
            text: TextLayout.TextLayoutResult,
            metrics: FontMetrics,
            height: Double,
        ): Double {
            var top = Double.POSITIVE_INFINITY
            var bottom = Double.NEGATIVE_INFINITY
            text.lines().forEachIndexed { line, value ->
                value.graphemes().forEach { grapheme ->
                    val ink = metrics.inkBounds(grapheme)
                    if (ink.bottom > ink.top) {
                        top = min(top, line * metrics.lineHeight() + ink.top)
                        bottom = max(bottom, line * metrics.lineHeight() + ink.bottom)
                    }
                }
            }
            return if (top.isFinite()) round(height / 2 - (top + bottom) / 2)
            else (height - text.height()) / 2
        }

        private fun scrollbar(
            window: ChoiceViewport.ChoiceWindow,
            track: Rect,
        ): WidgetFrame.Scrollbar {
            val thumbHeight = max(6.0, floor(track.height * window.choices().size / window.total()))
            val offset =
                if (window.start() + window.choices().size == window.total())
                    track.height - thumbHeight
                else
                    min(
                        track.height - thumbHeight,
                        round(track.height * window.start() / window.total()),
                    )
            return WidgetFrame.Scrollbar(
                track,
                Rect(track.x, track.y + offset, track.width, thumbHeight),
            )
        }

        private fun layout(
            text: ResolvedText,
            metrics: FontMetrics,
            width: Double,
            lines: Int,
        ): TextLayout.TextLayoutResult {
            val result = TextLayout().layout(text, metrics, TextLayout.TextLimits(width, lines))
            if (!result.isValid()) throw FallbackRequired(requireNotNull(result.problem()))
            return result
        }

        private fun widget(
            id: String,
            kind: Kind,
            bounds: Rect,
            text: ResolvedText,
            layout: TextLayout.TextLayoutResult,
            dx: Double,
            dy: Double,
            revealed: Int,
            selected: Boolean,
            enabled: Boolean,
            choiceId: String?,
            profile: SurfaceProfile,
            preferences: PresentationPreferences,
        ): Widget =
            Widget(
                id,
                kind,
                bounds,
                Rect(bounds.x + dx, bounds.y + dy, layout.width(), layout.height()),
                text,
                layout,
                revealed,
                selected,
                enabled,
                if (selected) requireNotNull(profile.theme).selectedScale() else 1.0,
                if (preferences.reducedMotion()) Duration.ZERO
                else requireNotNull(profile.theme).transition(),
                choiceId,
            )

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
    }
}
