package gg.grounds.dialogue.minestom

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.Action
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.port.ActionHandler
import gg.grounds.dialogue.core.port.ActorPort
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueInput
import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.core.validation.ValidationLimits
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import net.kyori.adventure.resource.ResourcePackStatus
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.player.PlayerResourcePackStatusEvent
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@EnvTest
class SessionCleanupTest {
    private class Fixture(env: Env) {
        val base = MinestomDialogueHostTest.Fixture(env)
        val host: MinestomDialogueHost
        val borrowed = Entity(EntityType.ARMOR_STAND)
        val valid = AtomicBoolean(true)
        val binding: MinestomSpeakerBinding
        val pack: UUID = UUID.randomUUID()

        init {
            done(env, base.host.closeAsync())
            borrowed.setInstance(base.player.instance, Pos(0.0, 42.0, 3.0, 180f, 0f)).join()
            binding =
                MinestomSpeakerBinding(
                    "npc1",
                    borrowed.instance,
                    {
                        MinestomSpeakerBinding.AnchorSnapshot(
                            borrowed.position,
                            borrowed.position.yaw(),
                            borrowed.position.add(0.0, 1.6, 0.0),
                            valid.get() && !borrowed.isRemoved,
                        )
                    },
                )
            host = MinestomDialogueHost(base.options)
            host.packState().expect(base.player, 1, pack, base.options.assets.pin())
            host
                .packState()
                .observe(
                    base.player,
                    1,
                    pack,
                    base.options.assets.pin(),
                    ResourcePackStatus.SUCCESSFULLY_LOADED,
                )
        }

        fun request(
            content: ValidatedDialogue,
            services: DialogueServices,
            actors: ActorPort?,
            renderer: String,
        ): MinestomDialogueRequest {
            val request = base.request("lifecycle", "v1")
            return MinestomDialogueRequest(
                request.requestId(),
                request.player(),
                request.hostGeneration(),
                content,
                services,
                request.context(),
                request.locale(),
                PresentationPreferences(renderer, Duration.ZERO, false, emptyMap()),
                mapOf("npc" to binding),
                actors,
                request.controls(),
                0,
            )
        }

        fun ui(): Long =
            base.player.instance!!
                .entities
                .count {
                    it.entityType == EntityType.TEXT_DISPLAY ||
                        it.entityType == EntityType.INTERACTION
                }
                .toLong()
    }

    @Test
    fun invalidAnchorClosesImmediately(env: Env) {
        val fixture = Fixture(env)
        val ticket =
            fixture.host.begin(
                fixture.request(fixture.base.content, fixture.base.services, null, "npc")
            )
        val handle = (done(env, ticket.result()) as DialogueStartResult.Started).handle
        assertTrue(fixture.ui() > 0)
        fixture.valid.set(false)
        assertEquals(DialogueEndReason.HOST_CHANGED, done(env, handle.completion()).reason())
        done(env, ticket.cancel(DialogueEndReason.CANCELLED))
        assertEquals(0, fixture.ui())
        assertFalse(fixture.borrowed.isRemoved)
        done(env, fixture.host.closeAsync())
    }

    @Test
    fun cleanupDrainsAllOwnedResources(env: Env) {
        val fixture = Fixture(env)
        val gate = CompletableFuture<Void>()
        val closes = AtomicInteger()
        val actors = ActorPort {
            CompletableFuture.completedFuture(
                object : ActorPort.ActorHandle {
                    override fun cue(cue: ActorPort.ActorCue): CompletionStage<Void> =
                        CompletableFuture.completedFuture(null)

                    override fun closeAsync(): CompletionStage<Void> {
                        closes.incrementAndGet()
                        return gate
                    }
                }
            )
        }
        val ticket =
            fixture.host.begin(
                fixture.request(fixture.base.content, fixture.base.services, actors, "npc")
            )
        done(env, ticket.result())
        val close = fixture.host.closeAsync()
        env.tick()
        env.tick()
        assertFalse(close.toCompletableFuture().isDone)
        assertEquals(1, closes.get())
        gate.complete(null)
        done(env, close)
        assertEquals(0, fixture.ui())
        assertFalse(fixture.borrowed.isRemoved)
        assertTrue(fixture.base.player.isOnline)
    }

    @Test
    fun instanceChangeAndLatePackStatusCannotResurrect(env: Env) {
        val fixture = Fixture(env)
        val oldWorld = fixture.base.player.instance!!
        val ticket =
            fixture.host.begin(
                fixture.request(fixture.base.content, fixture.base.services, null, "npc")
            )
        val handle = (done(env, ticket.result()) as DialogueStartResult.Started).handle
        fixture.base.player.setInstance(env.createFlatInstance(), Pos(0.0, 42.0, 0.0)).join()
        EventDispatcher.call(
            PlayerResourcePackStatusEvent(
                fixture.base.player,
                fixture.pack,
                ResourcePackStatus.SUCCESSFULLY_LOADED,
            )
        )
        assertEquals(DialogueEndReason.HOST_CHANGED, done(env, handle.completion()).reason())
        done(env, fixture.host.closeAsync())
        assertTrue(
            oldWorld.entities.none {
                it.entityType == EntityType.TEXT_DISPLAY || it.entityType == EntityType.INTERACTION
            }
        )
        assertFalse(fixture.borrowed.isRemoved)
    }

    @JvmRecord
    data class ActionContent(
        val content: ValidatedDialogue,
        val services: DialogueServices,
        val executions: AtomicInteger,
        val gate: CompletableFuture<ActionHandler.ActionResult>,
    )

    @Test
    fun npcKeepsBorrowedAnchorAcrossActionWait(env: Env) {
        val fixture = Fixture(env)
        val action = content(false)
        val ticket =
            fixture.host.begin(fixture.request(action.content, action.services, null, "npc"))
        val handle = (done(env, ticket.result()) as DialogueStartResult.Started).handle
        val view = handle.snapshot()
        done(
            env,
            handle.submit(
                DialogueInput.Confirm(
                    DialogueInput.InputStamp(view.sessionId(), view.renderRevision(), 1),
                    null,
                )
            ),
        )
        repeat(12) { env.tick() }
        assertFalse(handle.completion().toCompletableFuture().isDone)
        assertEquals(1, action.executions.get())
        assertEquals("npc", ticket.status().effectiveSurface)
        action.gate.complete(ActionHandler.ActionResult.SUCCESS)
        assertTrue(env.tickWhile({ handle.snapshot().nodeId() != "after" }, Duration.ofSeconds(2)))
        done(env, fixture.host.closeAsync())
    }

    @Test
    fun invalidAnchorBeforeEntryActionDoesNotDispatch(env: Env) {
        val fixture = Fixture(env)
        val action = content(true)
        fixture.valid.set(false)
        val ticket =
            fixture.host.begin(fixture.request(action.content, action.services, null, "hud"))
        assertInstanceOf(DialogueStartResult.Rejected::class.java, done(env, ticket.result()))
        assertEquals(0, action.executions.get())
        done(env, fixture.host.closeAsync())
    }

    companion object {
        @JvmStatic
        fun <T> done(env: Env, stage: CompletionStage<T>): T =
            MinestomDialogueHostTest.done(env, stage)

        @JvmStatic
        fun content(actionEntry: Boolean): ActionContent {
            val texts = TextCatalog(mapOf("en" to mapOf("line" to "Hello", "after" to "Done")))
            val builder =
                DialogueBuilder.dialogue("test:action-wait")
                    .node("start", Message("npc", "line", null, emptyList(), "wait"))
                    .node(
                        "wait",
                        Action("test:wait", emptyMap(), "after", "after", "after", "after"),
                    )
                    .node("after", Message("npc", "after", null, emptyList(), "end"))
                    .node("end", End("completed"))
            if (actionEntry) builder.entry("wait")
            val catalog =
                HostCatalog(
                    "v1",
                    setOf("npc"),
                    emptyMap(),
                    mapOf("test:wait" to HostCatalog.ProviderSpec(emptyMap(), false)),
                    emptySet(),
                    emptySet(),
                    false,
                )
            val validated =
                DialogueValidator()
                    .validate(builder.build(), texts, catalog, ValidationLimits.defaults())
                    .validated()!!
            assertNotNull(validated)
            val executions = AtomicInteger()
            val gate = CompletableFuture<ActionHandler.ActionResult>()
            val handler =
                object : ActionHandler {
                    override fun execute(
                        request: ActionHandler.ActionRequest
                    ): CompletionStage<ActionHandler.ActionResult> {
                        executions.incrementAndGet()
                        return gate
                    }

                    override fun reconcile(
                        reference: ActionHandler.ActionRef
                    ): CompletionStage<ActionHandler.ActionStatus> =
                        CompletableFuture.completedFuture(ActionHandler.ActionStatus.PENDING)
                }
            return ActionContent(
                validated,
                DialogueServices(
                    "v1",
                    emptyMap(),
                    mapOf("test:wait" to handler),
                    null,
                    TextResolver.catalogue(texts, "en"),
                    emptySet(),
                ),
                executions,
                gate,
            )
        }
    }
}
