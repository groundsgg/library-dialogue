package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.port.ActorPort
import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.port.HostBindings
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.core.validation.ValidationLimits
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.junit.jupiter.api.Assertions.assertTrue

internal class TestRig
@JvmOverloads
constructor(
    definition: DialogueDefinition =
        DialogueBuilder.dialogue("demo:runtime")
            .locales("en", "en", "de")
            .node("start", Message("npc", "hello", "demo:wave", emptyList(), "bye"))
            .node("bye", Message("npc", "bye", null, emptyList(), "end"))
            .node("end", End("completed"))
            .build(),
    catalog: HostCatalog =
        HostCatalog(
            "v1",
            setOf("npc"),
            emptyMap(),
            emptyMap(),
            setOf("demo:wave"),
            emptySet(),
            false,
        ),
) {
    @JvmField val scheduler = ManualScheduler()
    @JvmField val timings = mutableListOf<Pair<DialogueView, java.util.Optional<TypingTiming>>>()
    @JvmField var typingInvalidations = 0
    @JvmField var failTypingInvalidation = false
    @JvmField val frames = mutableListOf<DialogueView>()
    @JvmField val cues = mutableListOf<ActorPort.ActorCue>()
    @JvmField val content: ValidatedDialogue
    @JvmField val cancellation = CancellationToken()
    @JvmField var bindings: HostBindings
    @JvmField val runtime: DialogueRuntime

    init {
        val report =
            DialogueValidator()
                .validate(
                    definition,
                    TextCatalog(
                        mapOf(
                            "en" to
                                mapOf(
                                    "hello" to "Hello friend",
                                    "bye" to "Goodbye",
                                    "c" to "Continue",
                                    "speaker.npc" to "Mechanic",
                                ),
                            "de" to
                                mapOf(
                                    "hello" to "Hallo Freund",
                                    "bye" to "Tschüss",
                                    "c" to "Weiter",
                                    "speaker.npc" to "Mechaniker",
                                ),
                        )
                    ),
                    catalog,
                    ValidationLimits.defaults(),
                )
        if (!report.isValid()) throw AssertionError(report.problems())
        content = checkNotNull(report.validated())
        val renderer = DialogueRenderer {
            CompletableFuture.completedFuture(
                object : DialogueRenderer.PresentationHandle {
                    override fun replace(view: DialogueView): CompletionStage<Void> {
                        frames.add(view)
                        return CompletableFuture.completedFuture(null)
                    }

                    override fun replace(
                        view: DialogueView,
                        timing: java.util.Optional<TypingTiming>,
                    ): CompletionStage<Void> {
                        timings.add(view to timing)
                        return replace(view)
                    }

                    override fun invalidateTyping() {
                        typingInvalidations++
                        if (failTypingInvalidation) error("optional invalidation failure")
                    }

                    override fun reconfigure(
                        preferences: PresentationPreferences
                    ): CompletionStage<Void> = CompletableFuture.completedFuture(null)

                    override fun closeAsync(): CompletionStage<Void> =
                        CompletableFuture.completedFuture(null)
                }
            )
        }
        val actors = ActorPort {
            CompletableFuture.completedFuture(
                object : ActorPort.ActorHandle {
                    override fun cue(cue: ActorPort.ActorCue): CompletionStage<Void> {
                        cues.add(cue)
                        return CompletableFuture.completedFuture(null)
                    }

                    override fun closeAsync(): CompletionStage<Void> =
                        CompletableFuture.completedFuture(null)
                }
            )
        }
        bindings =
            HostBindings(
                "b1",
                emptyMap(),
                emptyMap(),
                null,
                TextResolver.catalogue(
                    content.texts(),
                    content.definition().locales().defaultTag(),
                ),
                renderer,
                actors,
                emptySet(),
            )
        runtime = DialogueRuntime.create(RuntimeOptions.defaults(scheduler))
    }

    fun request(id: String) =
        DialogueStart(
            id,
            "player-a",
            1,
            content,
            bindings,
            emptyMap(),
            LocaleContext("en", 0, "en"),
            PresentationPreferences.defaults(),
            cancellation,
            0,
        )

    fun start(): DialogueHandle {
        val future = runtime.start(request("start-1"))
        scheduler.drain()
        return (done(future) as DialogueStartResult.Started).handle
    }

    companion object {
        @JvmStatic
        fun <T> done(stage: CompletionStage<T>): T {
            val future = stage.toCompletableFuture()
            assertTrue(future.isDone, "stage must not remain pending")
            return future.join()
        }
    }
}
