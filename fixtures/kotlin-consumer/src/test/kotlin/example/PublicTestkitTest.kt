package example

import gg.grounds.dialogue.core.definition.*
import gg.grounds.dialogue.core.definition.DialogueDefinition.*
import gg.grounds.dialogue.core.port.*
import gg.grounds.dialogue.core.runtime.*
import gg.grounds.dialogue.core.text.*
import gg.grounds.dialogue.core.validation.*
import gg.grounds.dialogue.presentation.*
import gg.grounds.dialogue.testkit.*
import java.nio.file.*
import java.time.Duration
import java.util.concurrent.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PublicTestkitTest {
    private fun <T> done(stage: CompletionStage<T>): T {
        assertTrue(stage.toCompletableFuture().isDone)
        return stage.toCompletableFuture().join()
    }

    @Test
    fun realRuntimeUsesPublishedTestkitWithLocaleStaleInputAndLateAction() {
        val clock = ManualDialogueScheduler()
        val surface = RecordingDialogueSurface()
        val outcomes = RecordingOutcomeSink()
        val presenter =
            DialoguePresenter(
                mapOf("chat" to DialoguePresenter.SurfaceProfile.semantic(surface)),
                clock,
            )
        val definition =
            DialogueBuilder.dialogue("consumer:kotlin-kit")
                .locales("en", "en", "de")
                .node("start", Message("npc", "hello", null, emptyList(), "action"))
                .node(
                    "action",
                    Action("test:accept", emptyMap(), "after", "after", "after", "after"),
                )
                .node("after", Message("npc", "bye", null, emptyList(), "end"))
                .node("end", End("completed"))
                .build()
        val catalog =
            HostCatalog(
                "public",
                setOf("npc"),
                emptyMap(),
                mapOf("test:accept" to HostCatalog.ProviderSpec(emptyMap(), false)),
                emptySet(),
                emptySet(),
                false,
            )
        val texts =
            TextCatalog(
                mapOf(
                    "en" to mapOf("hello" to "Hello", "bye" to "Bye"),
                    "de" to mapOf("hello" to "Hallo", "bye" to "Tschüss"),
                )
            )
        val report =
            DialogueValidator().validate(definition, texts, catalog, ValidationLimits.defaults())
        assertTrue(report.isValid(), report.problems().toString())
        val pending = CompletableFuture<ActionHandler.ActionResult>()
        val late = CompletableFuture<ActionHandler.ActionResult>()
        val responses = java.util.ArrayDeque(listOf(pending, late))
        var calls = 0
        val action =
            object : ActionHandler {
                override fun execute(
                    request: ActionHandler.ActionRequest
                ): CompletionStage<ActionHandler.ActionResult> {
                    calls++
                    return responses.removeFirst()
                }

                override fun reconcile(
                    reference: ActionHandler.ActionRef
                ): CompletionStage<ActionHandler.ActionStatus> =
                    CompletableFuture.completedFuture(ActionHandler.ActionStatus.PENDING)
            }
        val bindings =
            HostBindings(
                "public",
                emptyMap(),
                mapOf("test:accept" to action),
                outcomes,
                TextResolver.catalogue(texts, "en"),
                presenter,
                null,
                emptySet(),
            )
        val runtime = DialogueRuntime.create(RuntimeOptions.defaults(clock))
        val started =
            runtime.start(
                DialogueStart(
                    "kotlin",
                    "player",
                    1,
                    checkNotNull(report.validated()),
                    bindings,
                    emptyMap(),
                    LocaleContext("en", 0, "en"),
                    PresentationPreferences("chat", Duration.ZERO, true, emptyMap()),
                    CancellationToken(),
                    0,
                )
            )
        clock.runUntilIdle()
        val handle = (done(started) as DialogueStartResult.Started).handle
        val before = handle.snapshot()
        handle.refreshLocale(LocaleContext("de", 1, "en"))
        clock.runUntilIdle()
        assertEquals("Hallo", handle.snapshot().text())
        val stale =
            handle.submit(
                DialogueInput.Confirm(
                    DialogueInput.InputStamp(before.sessionId(), before.renderRevision(), 1),
                    null,
                )
            )
        clock.runUntilIdle()
        assertEquals(InputResult.STALE, done(stale))
        assertEquals(0, calls)
        val current = handle.snapshot()
        handle.submit(
            DialogueInput.Confirm(
                DialogueInput.InputStamp(current.sessionId(), current.renderRevision(), 2),
                null,
            )
        )
        clock.runUntilIdle()
        assertEquals(1, calls)
        assertEquals(DialogueView.State.WAITING_FOR_PROVIDER, handle.snapshot().state())
        handle.refreshLocale(LocaleContext("en", 2, "en"))
        clock.runUntilIdle()
        assertEquals(1, calls)
        pending.complete(ActionHandler.ActionResult.SUCCESS)
        clock.runUntilIdle()
        assertEquals("Bye", handle.snapshot().text())
        assertEquals(1, calls)
        val closing = handle.stop(DialogueEndReason.CANCELLED)
        clock.runUntilIdle()
        done(closing)
        assertTrue(surface.snapshots().first().closed())
        val second =
            runtime.start(
                DialogueStart(
                    "kotlin-late",
                    "other",
                    1,
                    checkNotNull(report.validated()),
                    bindings,
                    emptyMap(),
                    LocaleContext("en", 0, "en"),
                    PresentationPreferences("chat", Duration.ZERO, true, emptyMap()),
                    CancellationToken(),
                    0,
                )
            )
        clock.runUntilIdle()
        val h2 = (done(second) as DialogueStartResult.Started).handle
        val v2 = h2.snapshot()
        h2.submit(
            DialogueInput.Confirm(
                DialogueInput.InputStamp(v2.sessionId(), v2.renderRevision(), 1),
                null,
            )
        )
        clock.runUntilIdle()
        assertEquals(2, calls)
        h2.stop(DialogueEndReason.CANCELLED)
        clock.runUntilIdle()
        val frames = surface.snapshots().last().frames().size
        late.complete(ActionHandler.ActionResult.SUCCESS)
        clock.advanceBy(Duration.ofSeconds(10))
        assertEquals(frames, surface.snapshots().last().frames().size)
        assertTrue(surface.snapshots().last().closed())
        assertEquals(2, calls)
        assertTrue(outcomes.events().isEmpty())
        runtime.closeAsync()
        clock.runUntilIdle()
        val origins =
            listOf(
                    DialogueRuntime::class.java,
                    DialoguePresenter::class.java,
                    ManualDialogueScheduler::class.java,
                )
                .joinToString("") { type ->
                    val uri = type.protectionDomain.codeSource.location.toURI()
                    assertTrue(uri.path.endsWith(".jar"))
                    "MAVEN_ORIGIN ${type.name} ${uri.toASCIIString()}\n"
                }
        val log = Path.of("build/test-origins/kotlin.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, origins)
    }
}
