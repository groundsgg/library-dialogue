package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.Action
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.port.ActionHandler
import gg.grounds.dialogue.core.port.ActionHandler.*
import gg.grounds.dialogue.core.port.HostBindings
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.validation.HostCatalog
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class DialogueActionsTest {
    private class Jobs : ActionHandler {
        var executes = 0
        var effects = 0
        lateinit var request: ActionRequest
        val response = CompletableFuture<ActionResult>()
        val registry = mutableMapOf<UUID, ActionStatus>()

        override fun execute(request: ActionRequest): CompletionStage<ActionResult> {
            executes++
            this.request = request
            registry.putIfAbsent(checkNotNull(request.reference()).requestId, ActionStatus.PENDING)
            response.thenAccept { result ->
                if (result == ActionResult.SUCCESS) {
                    effects++
                    registry[checkNotNull(request.reference()).requestId] = ActionStatus.SUCCEEDED
                }
            }
            return response
        }

        override fun reconcile(reference: ActionRef): CompletionStage<ActionStatus> =
            CompletableFuture.completedFuture(
                registry.getOrDefault(reference.requestId, ActionStatus.UNAVAILABLE)
            )
    }

    private fun rig(jobs: Jobs): TestRig {
        val definition =
            DialogueBuilder.dialogue("demo:actions")
                .locales("en", "en", "de")
                .node("start", Message("npc", "hello", null, emptyList(), "execute"))
                .node(
                    "execute",
                    Action(
                        "demo:accept",
                        emptyMap(),
                        "success",
                        "rejected",
                        "unavailable",
                        "uncertain",
                    ),
                )
                .node("success", Message("npc", "bye", null, emptyList(), "end"))
                .node("rejected", Message("npc", "bye", null, emptyList(), "end"))
                .node("unavailable", Message("npc", "bye", null, emptyList(), "end"))
                .node("uncertain", Message("npc", "hello", null, emptyList(), "end"))
                .node("end", End("completed"))
                .build()
        val rig =
            TestRig(
                definition,
                HostCatalog(
                    "v1",
                    setOf("npc"),
                    emptyMap(),
                    mapOf("demo:accept" to HostCatalog.ProviderSpec(emptyMap(), true)),
                    emptySet(),
                    emptySet(),
                    false,
                ),
            )
        rig.bindings =
            HostBindings(
                "jobs",
                emptyMap(),
                mapOf("demo:accept" to jobs),
                null,
                rig.bindings.texts(),
                rig.bindings.renderer(),
                null,
                emptySet(),
            )
        return rig
    }

    private fun execute(rig: TestRig): DialogueHandle {
        val handle = rig.start()
        handle.submit(DialogueInputTest.confirm(handle, 1))
        rig.scheduler.drain()
        handle.submit(DialogueInputTest.confirm(handle, 2))
        rig.scheduler.drain()
        assertEquals(DialogueView.State.WAITING_FOR_PROVIDER, handle.snapshot().state())
        return handle
    }

    @Test
    fun localeChangesDuringAction() {
        val jobs = Jobs()
        val rig = rig(jobs)
        val handle = execute(rig)
        assertEquals(1, jobs.registry.size)
        val refresh = handle.refreshLocale(LocaleContext("de", 1, "en"))
        rig.scheduler.drain()
        assertEquals(RefreshResult.UPDATED, TestRig.done(refresh))
        jobs.response.complete(ActionResult.SUCCESS)
        rig.scheduler.drain()
        assertEquals("success", handle.snapshot().nodeId())
        assertEquals("Tschüss", handle.snapshot().text())
        assertEquals(1, jobs.executes)
        assertEquals(1, jobs.effects)
    }

    @Test
    fun lateSuccessNeverLeavesUncertainPath() {
        val jobs = Jobs()
        val rig = rig(jobs)
        val handle = execute(rig)
        val reference = handle.snapshot().pendingAction()
        assertNotNull(reference)
        rig.scheduler.advance(Duration.ofSeconds(10))
        assertEquals("uncertain", handle.snapshot().nodeId())
        val visit = handle.snapshot().nodeVisitId()
        jobs.response.complete(ActionResult.SUCCESS)
        rig.scheduler.drain()
        assertEquals(visit, handle.snapshot().nodeVisitId())
        assertEquals(1, jobs.effects)
        assertEquals(ActionStatus.SUCCEEDED, TestRig.done(jobs.reconcile(checkNotNull(reference))))
        assertEquals(1, jobs.executes)
    }

    @Test
    fun cancelledActionDoesNotReopenUi() {
        val jobs = Jobs()
        val rig = rig(jobs)
        val handle = execute(rig)
        val done = handle.stop(DialogueEndReason.DISCONNECTED)
        rig.scheduler.drain()
        assertEquals(DialogueEndReason.DISCONNECTED, TestRig.done(done).reason())
        jobs.response.complete(ActionResult.SUCCESS)
        rig.scheduler.drain()
        assertEquals(DialogueView.State.CLOSED, handle.snapshot().state())
        assertEquals(1, jobs.executes)
    }

    @Test
    fun allExplicitActionResultsUseTheirOwnPaths() {
        for ((result, path) in
            mapOf(
                ActionResult.SUCCESS to "success",
                ActionResult.REJECTED to "rejected",
                ActionResult.UNAVAILABLE to "unavailable",
                ActionResult.UNCERTAIN to "uncertain",
            )) {
            val jobs = Jobs()
            val rig = rig(jobs)
            val handle = execute(rig)
            jobs.response.complete(result)
            rig.scheduler.drain()
            assertEquals(path, handle.snapshot().nodeId())
            assertEquals(1, jobs.executes)
        }
    }

    @Test
    fun surfaceSwitchDuringActionKeepsTheJob() {
        val jobs = Jobs()
        val rig = rig(jobs)
        val handle = execute(rig)
        val visit = handle.snapshot().nodeVisitId()
        val update =
            handle.updatePreferences(
                PresentationPreferences("npc", Duration.ofMillis(35), false, emptyMap())
            )
        rig.scheduler.drain()
        TestRig.done(update)
        assertEquals(visit, handle.snapshot().nodeVisitId())
        assertEquals(1, jobs.executes)
        jobs.response.complete(ActionResult.SUCCESS)
        rig.scheduler.drain()
        assertEquals("success", handle.snapshot().nodeId())
        assertEquals(1, jobs.executes)
    }
}
