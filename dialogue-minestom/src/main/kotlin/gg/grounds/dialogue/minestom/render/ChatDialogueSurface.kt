package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.minestom.input.ChatInputTokens
import gg.grounds.dialogue.minestom.input.SurfaceInputTargets
import gg.grounds.dialogue.minestom.input.TargetSnapshot
import gg.grounds.dialogue.minestom.internal.ConnectionGuard
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueSurface
import gg.grounds.dialogue.presentation.UiLabelSource
import gg.grounds.dialogue.presentation.WidgetFrame
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.Function
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer

/** Semantic, complete sections with plain text and server-issued click capabilities. */
class ChatDialogueSurface(
    private val guard: ConnectionGuard,
    private val targets: SurfaceInputTargets,
    private val tokens: ChatInputTokens,
    private val sink: Function<Component, CompletionStage<Void>>,
    private val labels: UiLabelSource,
    private val controls: DialoguePresenter.Controls,
    private val route: String,
) : DialogueSurface {
    init {
        require(route.matches(Regex("/[a-z0-9_]+"))) { "Invalid route" }
    }

    override fun open(
        context: DialogueSurface.SurfaceContext
    ): CompletionStage<DialogueSurface.SurfaceHandle> =
        CompletableFuture.completedFuture(Handle(context))

    private inner class Handle(private val context: DialogueSurface.SurfaceContext) :
        DialogueSurface.SurfaceHandle {
        @Volatile private var closed = false
        private val writes = ArrayList<CompletableFuture<Void>>()
        private var closing: CompletionStage<Void>? = null
        private var last: Any? = null
        @Volatile private var epoch = 0L

        override fun replace(frame: WidgetFrame): CompletionStage<Void> {
            if (closed || !guard.isCurrent() || context.cancellation.isCancelled()) {
                return CompletableFuture.failedFuture(CancellationException())
            }
            val view = frame.semantic()
            val key =
                listOf(
                    view.sessionId(),
                    view.nodeVisitId(),
                    view.localeTag(),
                    view.speaker(),
                    view.text(),
                    view.choices(),
                    view.hint(),
                    view.notice(),
                )
            val choices = linkedMapOf<String, WidgetFrame.Rect>()
            view
                .choices()
                .filter { it.availability == DialogueView.Availability.AVAILABLE }
                .forEach { choices[it.id] = WidgetFrame.Rect(0.0, 0.0, 0.0, 0.0) }
            val snapshot =
                TargetSnapshot(
                    view.sessionId(),
                    view.renderRevision(),
                    view.nodeVisitId(),
                    context.generation,
                    choices,
                )
            if (key == last) {
                targets.publish(frame, context.generation, choices)
                tokens.rebase(snapshot)
                return CompletableFuture.completedFuture(null)
            }
            tokens.clear()
            targets.invalidateCurrent(context.generation)
            val operation = ++epoch
            val reasons = linkedMapOf<String, String>()
            view
                .choices()
                .filter { it.availability != DialogueView.Availability.AVAILABLE }
                .forEach { reasons[it.id] = checkNotNull(it.unavailableReason) }
            val ui =
                labels.resolve(
                    UiLabelSource.LabelRequest(
                        view.localeTag(),
                        view.hint(),
                        controls,
                        view.notice(),
                        reasons,
                    )
                )
            var output =
                Component.text(view.speaker(), NamedTextColor.GOLD)
                    .appendNewline()
                    .append(Component.text(view.text(), NamedTextColor.WHITE))
            for (choice in view.choices()) {
                var row =
                    Component.text(
                        choice.label,
                        if (choice.availability == DialogueView.Availability.AVAILABLE)
                            NamedTextColor.YELLOW
                        else NamedTextColor.GRAY,
                    )
                if (
                    choice.availability == DialogueView.Availability.AVAILABLE &&
                        view.state() != DialogueView.State.WAITING_FOR_PROVIDER
                ) {
                    row =
                        row.clickEvent(
                            ClickEvent.runCommand("$route ${tokens.issue(choice.id, snapshot)}")
                        )
                } else if (choice.availability != DialogueView.Availability.AVAILABLE) {
                    row =
                        row.append(
                            Component.text(" · " + ui.choiceReasons().getOrDefault(choice.id, ""))
                        )
                }
                output = output.appendNewline().append(row)
            }
            var hint = Component.text(ui.hint(), NamedTextColor.GRAY)
            if (view.hint() != DialogueView.HintPhase.BUSY && choices.isEmpty()) {
                hint =
                    hint.clickEvent(ClickEvent.runCommand("$route ${tokens.issue(null, snapshot)}"))
            }
            output = output.appendNewline().append(hint)
            if (ui.notice().isNotEmpty())
                output =
                    output.appendNewline().append(Component.text(ui.notice(), NamedTextColor.GRAY))
            if (
                GsonComponentSerializer.gson()
                    .serialize(output)
                    .toByteArray(StandardCharsets.UTF_8)
                    .size > 65536
            ) {
                return CompletableFuture.failedFuture(IllegalArgumentException("FRAME_BUDGET"))
            }
            val promise = CompletableFuture<Void>()
            val transport = CompletableFuture<Void>()
            writes.removeIf(CompletableFuture<Void>::isDone)
            writes += transport
            try {
                sink.apply(output).whenComplete { _, error ->
                    if (error == null) transport.complete(null)
                    else transport.completeExceptionally(error)
                    when {
                        error != null -> promise.completeExceptionally(error)
                        closed ||
                            epoch != operation ||
                            !guard.isCurrent() ||
                            context.cancellation.isCancelled() ->
                            promise.completeExceptionally(CancellationException())
                        else -> {
                            last = key
                            targets.publish(frame, context.generation, choices)
                            promise.complete(null)
                        }
                    }
                }
            } catch (error: Throwable) {
                transport.completeExceptionally(error)
                promise.completeExceptionally(error)
            }
            return promise.minimalCompletionStage()
        }

        override fun closeAsync(): CompletionStage<Void> {
            closing?.let {
                return it
            }
            closed = true
            epoch++
            tokens.clear()
            targets.clear(context.generation)
            closing = CompletableFuture.allOf(*writes.toTypedArray()).minimalCompletionStage()
            return requireNotNull(closing)
        }
    }
}
