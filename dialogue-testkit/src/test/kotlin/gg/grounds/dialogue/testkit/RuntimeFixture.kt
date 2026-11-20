package gg.grounds.dialogue.testkit

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.port.HostBindings
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueHandle
import gg.grounds.dialogue.core.runtime.DialogueRuntime
import gg.grounds.dialogue.core.runtime.DialogueStart
import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.runtime.RuntimeOptions
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.core.validation.ValidationLimits
import java.util.concurrent.CompletionStage

internal class RuntimeFixture {
    val clock = ManualDialogueScheduler()
    val runtime: DialogueRuntime = DialogueRuntime.create(RuntimeOptions.defaults(clock))
    val content: ValidatedDialogue

    init {
        val definition =
            DialogueBuilder.dialogue("test:hello")
                .locales("en", "en", "de")
                .node("start", Message("npc", "hello", null, emptyList(), "end"))
                .node("end", End("completed"))
                .build()
        val report =
            DialogueValidator()
                .validate(
                    definition,
                    TextCatalog(
                        mapOf("en" to mapOf("hello" to "Hello"), "de" to mapOf("hello" to "Hallo"))
                    ),
                    HostCatalog(
                        "v1",
                        setOf("npc"),
                        emptyMap(),
                        emptyMap(),
                        emptySet(),
                        emptySet(),
                        false,
                    ),
                    ValidationLimits.defaults(),
                )
        if (!report.isValid()) throw AssertionError(report.problems())
        content = checkNotNull(report.validated())
    }

    fun start(renderer: DialogueRenderer, id: String): DialogueHandle {
        val bindings =
            HostBindings(
                "v1",
                emptyMap(),
                emptyMap(),
                null,
                TextResolver.catalogue(content.texts(), "en"),
                renderer,
                null,
                emptySet(),
            )
        val stage =
            runtime.start(
                DialogueStart(
                    id,
                    id,
                    1,
                    content,
                    bindings,
                    emptyMap(),
                    LocaleContext("en", 0, "en"),
                    PresentationPreferences.defaults(),
                    CancellationToken(),
                    0,
                )
            )
        clock.runUntilIdle()
        return (done(stage) as DialogueStartResult.Started).handle
    }

    companion object {
        fun <T> done(stage: CompletionStage<T>): T {
            val future = stage.toCompletableFuture()
            if (!future.isDone) throw AssertionError("pending")
            return future.join()
        }
    }
}
