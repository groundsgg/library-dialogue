package gg.grounds.dialogue.minestom.input

import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueInput
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.InputResult
import gg.grounds.dialogue.core.runtime.RuntimeOptions
import gg.grounds.dialogue.minestom.MinestomConnectionScope
import gg.grounds.dialogue.minestom.MinestomSpeakerBinding.AnchorSnapshot
import gg.grounds.dialogue.minestom.geometry.NpcHitTest
import gg.grounds.dialogue.minestom.geometry.NpcPanelTransform
import gg.grounds.dialogue.minestom.internal.MinestomDialogueScheduler
import gg.grounds.dialogue.minestom.render.NpcDialogueSurface
import gg.grounds.dialogue.minestom.render.RenderFixtures
import gg.grounds.dialogue.presentation.WidgetFrame
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.entity.metadata.other.InteractionMeta
import net.minestom.server.event.player.PlayerPacketEvent
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.client.ClientPacket
import net.minestom.server.network.packet.client.play.ClientAttackPacket
import net.minestom.server.network.packet.client.play.ClientHeldItemChangePacket
import net.minestom.server.network.packet.client.play.ClientInteractEntityPacket
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket
import net.minestom.server.network.packet.client.play.ClientUseItemPacket
import net.minestom.server.network.packet.server.play.AcknowledgeBlockChangePacket
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import net.minestom.testing.TestConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@EnvTest
class InputSafetyTest {
    private fun <T> done(env: Env, stage: CompletionStage<T>): T {
        assertTrue(env.tickWhile({ !stage.toCompletableFuture().isDone }, Duration.ofSeconds(2)))
        return stage.toCompletableFuture().join()
    }

    private class Fixture(env: Env, profile: InputProfile = InputProfile.defaults()) :
        MinestomInputAdapter.Target {
        val player: Player
        val connection: TestConnection
        val adapter: MinestomInputAdapter
        val submitted = mutableListOf<DialogueInput>()
        val now = AtomicLong()
        val impulse = AtomicLong()
        var view: DialogueView = RenderFixtures.view("Hello", 5)
        var targets: TargetSnapshot
        var geometry: NpcDialogueSurface.Geometry? = null
        var hidden = false
        var visible = true
        var submitGate: CompletionStage<InputResult> =
            CompletableFuture.completedFuture(InputResult.ACCEPTED)

        init {
            connection = env.createConnection()
            player = connection.connect(env.createFlatInstance(), Pos(0.0, 42.0, 4.0, 180f, 0f))
            val nativeClock = MinestomDialogueScheduler(player.instance)
            val clock =
                object : DialogueScheduler {
                    override fun execute(runnable: Runnable) = nativeClock.execute(runnable)

                    override fun schedule(
                        delay: Duration,
                        runnable: Runnable,
                    ): DialogueScheduler.ScheduledTask = nativeClock.schedule(delay, runnable)

                    override fun nanoTime(): Long = now.get()
                }
            targets =
                TargetSnapshot(
                    view.sessionId(),
                    view.renderRevision(),
                    view.nodeVisitId(),
                    1,
                    mapOf(
                        "ask" to WidgetFrame.Rect(326.0, 0.0, 240.0, 40.0),
                        "leave" to WidgetFrame.Rect(326.0, 46.0, 240.0, 40.0),
                    ),
                )
            adapter =
                MinestomInputAdapter(
                    MinestomConnectionScope(player, 1, player.instance),
                    profile,
                    clock,
                    CancellationToken(),
                    this,
                    NpcHitTest.OcclusionTest { _, _ -> visible },
                )
        }

        override fun view(): DialogueView = view

        override fun targets(): TargetSnapshot = targets

        override fun npcGeometry(): NpcDialogueSurface.Geometry? = geometry

        override fun effectiveSurface(): String = if (geometry == null) "hud" else "npc"

        override fun hiddenHotbar(): Boolean = hidden

        override fun nextImpulse(): Long = impulse.incrementAndGet()

        override fun submit(input: DialogueInput): CompletionStage<InputResult> {
            submitted += input
            return submitGate
        }

        fun packet(env: Env, packet: ClientPacket): PlayerPacketEvent {
            val event = PlayerPacketEvent(player, packet)
            adapter.receive(event)
            env.tick()
            env.tick()
            return event
        }

        fun aim(id: String) {
            val transform =
                NpcPanelTransform.from(
                        AnchorSnapshot(Vec(0.0, 42.0, 0.0), 0f, Vec(0.0, 43.6, 0.0), true),
                        player.position.add(0.0, player.eyeHeight, 0.0),
                    )
                    .withSpeechHeight(40.0)
            geometry =
                NpcDialogueSurface.Geometry(
                    transform,
                    targets.choices().values.toList(),
                    1,
                    targets.choices(),
                )
            val bounds = targets.choices()[id]!!
            val target =
                transform.widgetToWorld(
                    bounds.x + bounds.width / 2,
                    bounds.y + bounds.height / 2,
                )
            player.setView(
                player.position.withLookAt(target).yaw,
                player.position.add(0.0, player.eyeHeight, 0.0).asPos().withLookAt(target).pitch,
            )
        }
    }

    @Test
    fun occludedSelectedNpcChoiceCannotConfirm(env: Env) {
        val fixture = Fixture(env)
        fixture.aim("ask")
        done(env, fixture.adapter.activate())
        fixture.visible = false
        fixture.now.set(400_000_000)
        fixture.packet(env, ClientUseItemPacket(PlayerHand.MAIN, 1, 0f, 0f))
        assertTrue(
            fixture.submitted.isEmpty(),
            "Occlusion must block confirmation as well as hover",
        )
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun backFacingSelectedNpcChoiceCannotConfirm(env: Env) {
        val fixture = Fixture(env)
        fixture.aim("ask")
        val transform = fixture.geometry!!.transform()
        val bounds = fixture.targets.choices()["ask"]!!
        val center =
            transform.widgetToWorld(
                bounds.x + bounds.width / 2,
                bounds.y + bounds.height / 2,
            )
        val eye = center.sub(transform.planeFor(bounds).normal.mul(3.0))
        val look = eye.asPos().withLookAt(center)
        done(
            env,
            fixture.player.teleport(
                eye.sub(0.0, fixture.player.eyeHeight, 0.0).asPos().withView(look.yaw, look.pitch)
            ),
        )
        done(env, fixture.adapter.activate())
        fixture.now.set(400_000_000)
        fixture.packet(env, ClientUseItemPacket(PlayerHand.MAIN, 1, 0f, 0f))
        assertTrue(
            fixture.submitted.isEmpty(),
            "A panel back must not confirm the selected front answer",
        )
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun pointingAtUnavailableNpcChoiceCannotConfirmAnotherAnswer(env: Env) {
        val fixture = Fixture(env)
        fixture.aim("leave")
        val view = fixture.view
        fixture.view =
            DialogueView(
                view.sessionId(),
                view.contentPin(),
                view.nodeId(),
                view.nodeVisitId(),
                view.state(),
                view.stateRevision(),
                view.renderRevision(),
                view.localeTag(),
                view.speaker(),
                view.text(),
                listOf(
                    view.choices().first(),
                    DialogueView.ChoiceView(
                        "leave",
                        "Leave",
                        DialogueView.Availability.UNAVAILABLE,
                        "CHOICE_UNAVAILABLE",
                    ),
                ),
                view.selectedChoiceId(),
                view.totalGraphemes(),
                view.revealedGraphemes(),
                view.hint(),
                view.notice(),
                view.pendingAction(),
            )
        fixture.targets =
            TargetSnapshot(
                view.sessionId(),
                view.renderRevision(),
                view.nodeVisitId(),
                1,
                mapOf("ask" to fixture.targets.choices()["ask"]!!),
            )
        done(env, fixture.adapter.activate())
        fixture.now.set(400_000_000)
        fixture.packet(env, ClientUseItemPacket(PlayerHand.MAIN, 1, 0f, 0f))
        assertTrue(
            fixture.submitted.isEmpty(),
            "Clicking an unavailable card must not confirm another answer",
        )
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun queuedConfirmFromPreviousPageIsRejectedEvenWhenCoreRevisionsStayEqual(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.adapter.activate())
        val gate = CompletableFuture<InputResult>()
        fixture.submitGate = gate
        fixture.packet(env, ClientHeldItemChangePacket(1))
        fixture.now.set(400_000_000)
        fixture.packet(env, ClientUseItemPacket(PlayerHand.MAIN, 1, 0f, 0f))
        val old = fixture.targets
        fixture.targets =
            TargetSnapshot(
                old.sessionId(),
                old.renderRevision(),
                old.nodeVisitId(),
                old.surfaceGeneration(),
                old.choices(),
                old.stateRevision(),
                old.navigation(),
            )
        fixture.submitGate = CompletableFuture.completedFuture(InputResult.ACCEPTED)
        gate.complete(InputResult.ACCEPTED)
        repeat(10) { env.tick() }
        assertEquals(
            1,
            fixture.submitted.size,
            "An old issued frame cannot confirm the new viewport",
        )
        fixture.now.set(800_000_000)
        fixture.packet(env, ClientUseItemPacket(PlayerHand.MAIN, 2, 0f, 0f))
        assertEquals(2, fixture.submitted.size)
        assertInstanceOf(DialogueInput.Confirm::class.java, fixture.submitted.last())
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun inputPreparationHasDeadlineAndOwnsLateCatcher(env: Env) {
        val fixture = Fixture(env)
        val clock = MinestomDialogueScheduler(fixture.player.instance)
        val guard = MinestomConnectionScope(fixture.player, 1, fixture.player.instance)
        val gate = CompletableFuture<Void>()
        val budget =
            RuntimeOptions.Budgets(
                Duration.ofSeconds(2),
                Duration.ofMillis(25),
                Duration.ofSeconds(10),
                Duration.ofSeconds(1),
            )
        val adapter =
            MinestomInputAdapter(
                guard,
                InputProfile.defaults(),
                clock,
                CancellationToken(),
                fixture,
                NpcHitTest.OcclusionTest { _, _ -> true },
                budget,
            ) {
                PrivateInteractionCatcher.open(guard).thenCompose { catcher ->
                    gate.thenApply { catcher }
                }
            }
        val active = adapter.activate()
        assertTrue(env.tickWhile({ !active.toCompletableFuture().isDone }, Duration.ofSeconds(1)))
        assertThrows(CompletionException::class.java) { active.toCompletableFuture().join() }
        val close = adapter.closeAsync()
        env.tick()
        assertFalse(close.toCompletableFuture().isDone)
        gate.complete(null)
        done(env, close)
        assertTrue(
            fixture.player.instance.entities.none { it.entityType == EntityType.INTERACTION }
        )
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun queuedScrollWaitsUntilTargetsCatchUpWithCoreView(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.adapter.activate())
        fixture.packet(env, ClientHeldItemChangePacket(1))
        val view = fixture.view
        fixture.view = view.withSelection("leave", 2, 2)
        fixture.packet(env, ClientHeldItemChangePacket(2))
        assertEquals(1, fixture.submitted.size)
        fixture.targets =
            TargetSnapshot(view.sessionId(), 2, view.nodeVisitId(), 1, fixture.targets.choices())
        fixture.adapter.tick()
        assertTrue(env.tickWhile({ fixture.submitted.size < 2 }, Duration.ofSeconds(1)))
        assertEquals("ask", (fixture.submitted.last() as DialogueInput.Select).choiceId)
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun closeRepairsPredictionOfQueuedCancelledPlacement(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.adapter.activate())
        val gate = CompletableFuture<InputResult>()
        fixture.submitGate = gate
        fixture.now.set(400_000_000)
        val acknowledgements =
            fixture.connection.trackIncoming(AcknowledgeBlockChangePacket::class.java)
        fixture.packet(env, ClientUseItemPacket(PlayerHand.MAIN, 1, 0f, 0f))
        fixture.packet(
            env,
            ClientPlayerBlockPlacementPacket(
                PlayerHand.MAIN,
                Vec(0.0, 41.0, 0.0),
                BlockFace.TOP,
                .5f,
                1f,
                .5f,
                false,
                false,
                2,
            ),
        )
        done(env, fixture.adapter.closeAsync())
        assertTrue(acknowledgements.collect().any { it.sequence() == 2 })
        gate.complete(InputResult.ACCEPTED)
    }

    @Test
    fun queuedScrollWaitsForPublishedCoreSelection(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.adapter.activate())
        val gate = CompletableFuture<InputResult>()
        fixture.submitGate = gate
        fixture.packet(env, ClientHeldItemChangePacket(1))
        fixture.packet(env, ClientHeldItemChangePacket(2))
        assertEquals(1, fixture.submitted.size)
        val view = fixture.view
        fixture.view = view.withSelection("leave", 2, 2)
        fixture.targets =
            TargetSnapshot(view.sessionId(), 2, view.nodeVisitId(), 1, fixture.targets.choices())
        fixture.submitGate = CompletableFuture.completedFuture(InputResult.ACCEPTED)
        gate.complete(InputResult.ACCEPTED)
        assertTrue(env.tickWhile({ fixture.submitted.size < 2 }, Duration.ofSeconds(2)))
        assertEquals("ask", (fixture.submitted.last() as DialogueInput.Select).choiceId)
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun delayedUseInteractBurstKeepsArrivalTime(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.adapter.activate())
        val gate = CompletableFuture<InputResult>()
        fixture.submitGate = gate
        fixture.now.set(400_000_000)
        fixture.packet(env, ClientUseItemPacket(PlayerHand.MAIN, 1, 0f, 0f))
        fixture.now.incrementAndGet()
        fixture.packet(env, ClientInteractEntityPacket(1, PlayerHand.MAIN, Vec.ZERO, false))
        assertEquals(1, fixture.submitted.size)
        fixture.now.set(2_000_000_000)
        fixture.submitGate = CompletableFuture.completedFuture(InputResult.ACCEPTED)
        gate.complete(InputResult.ACCEPTED)
        repeat(5) { env.tick() }
        assertEquals(1, fixture.submitted.size)
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun attackIsConfigurableAndOtherActionsCannotConfirm(env: Env) {
        val fixture =
            Fixture(
                env,
                InputProfile(
                    setOf(InputProfile.Trigger.ATTACK),
                    setOf(InputProfile.Trigger.ATTACK),
                    InputProfile.Trigger.ATTACK,
                    true,
                    Duration.ofMillis(350),
                ),
            )
        done(env, fixture.adapter.activate())
        fixture.now.set(400_000_000)
        fixture.packet(env, ClientUseItemPacket(PlayerHand.OFF, 1, 0f, 0f))
        assertTrue(fixture.submitted.isEmpty())
        val catcherId =
            fixture.player.instance.entities
                .first { it.entityType == EntityType.INTERACTION }
                .entityId
        fixture.packet(env, ClientAttackPacket(catcherId))
        assertEquals(1, fixture.submitted.size)
        assertInstanceOf(DialogueInput.Confirm::class.java, fixture.submitted.first())
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun coveredHotbarDoesNotKeepSendingSelectionFences(env: Env) {
        val fixture = Fixture(env)
        fixture.hidden = true
        done(env, fixture.adapter.activate())
        val pings =
            fixture.connection.trackIncoming(
                net.minestom.server.network.packet.server.common.PingPacket::class.java
            )
        fixture.packet(env, ClientHeldItemChangePacket(1))
        repeat(5) {
            fixture.adapter.tick()
            env.tick()
        }
        assertTrue(pings.collect().isEmpty())
        assertEquals(0, fixture.player.heldSlot.toInt())
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun airCatcherConsumesNativeInteractionWithoutHandItems(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.adapter.activate())
        val entities =
            fixture.player.instance.entities.filter { it.entityType == EntityType.INTERACTION }
        assertEquals(1, entities.size)
        val metadata = entities.first().entityMeta as InteractionMeta
        assertTrue(metadata.response)
        assertTrue(fixture.player.itemInMainHand.isAir)
        assertTrue(fixture.player.itemInOffHand.isAir)
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun emptyHandsAirUseIsCaptured(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.adapter.activate())
        assertTrue(
            fixture.player.instance.entities
                .filter { it.entityType == EntityType.INTERACTION }
                .all { it.viewers == setOf(fixture.player) }
        )
        fixture.now.set(400_000_000)
        assertTrue(fixture.packet(env, ClientUseItemPacket(PlayerHand.MAIN, 1, 0f, 0f)).isCancelled)
        assertEquals(1, fixture.submitted.size)
        assertInstanceOf(DialogueInput.Confirm::class.java, fixture.submitted.first())
        fixture.packet(env, ClientInteractEntityPacket(1, PlayerHand.MAIN, Vec.ZERO, false))
        assertEquals(1, fixture.submitted.size)
        done(env, fixture.adapter.closeAsync())
        assertTrue(
            fixture.player.instance.entities.none { it.entityType == EntityType.INTERACTION }
        )
    }

    @Test
    fun scrollWrapKeepsServerSlot(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.adapter.activate())
        assertTrue(fixture.packet(env, ClientHeldItemChangePacket(8)).isCancelled)
        assertEquals(0, fixture.player.heldSlot.toInt())
        assertEquals("leave", (fixture.submitted.first() as DialogueInput.Select).choiceId)
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun inventoryPredictionUsesCurrentState(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.adapter.activate())
        fixture.player.itemInMainHand = ItemStack.of(Material.DIAMOND)
        fixture.player.itemInOffHand = ItemStack.of(Material.SHIELD)
        val stacks = fixture.player.inventory.itemStacks.clone()
        val acknowledgement =
            fixture.connection.trackIncoming(AcknowledgeBlockChangePacket::class.java)
        fixture.now.set(400_000_000)
        assertTrue(
            fixture
                .packet(
                    env,
                    ClientPlayerBlockPlacementPacket(
                        PlayerHand.MAIN,
                        Vec(0.0, 41.0, 0.0),
                        BlockFace.TOP,
                        .5f,
                        1f,
                        .5f,
                        false,
                        false,
                        4,
                    ),
                )
                .isCancelled
        )
        assertTrue(
            fixture
                .packet(
                    env,
                    ClientPlayerActionPacket(
                        ClientPlayerActionPacket.Status.DROP_ITEM,
                        Vec.ZERO,
                        BlockFace.TOP,
                        5,
                    ),
                )
                .isCancelled
        )
        assertTrue(
            fixture
                .packet(
                    env,
                    ClientPlayerActionPacket(
                        ClientPlayerActionPacket.Status.SWAP_ITEM_HAND,
                        Vec.ZERO,
                        BlockFace.TOP,
                        6,
                    ),
                )
                .isCancelled
        )
        assertTrue(
            fixture
                .packet(
                    env,
                    ClientPlayerActionPacket(
                        ClientPlayerActionPacket.Status.STARTED_DIGGING,
                        Vec(0.0, 41.0, 0.0),
                        BlockFace.TOP,
                        7,
                    ),
                )
                .isCancelled
        )
        assertArrayEquals(stacks, fixture.player.inventory.itemStacks)
        assertEquals(0, fixture.player.heldSlot.toInt())
        assertFalse(acknowledgement.collect().isEmpty())
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun stationaryHoverDoesNotUndoScroll(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.adapter.activate())
        fixture.aim("ask")
        fixture.adapter.tick()
        env.tick()
        env.tick()
        val before = fixture.submitted.size
        fixture.packet(env, ClientHeldItemChangePacket(1))
        assertEquals(before + 1, fixture.submitted.size)
        fixture.adapter.tick()
        env.tick()
        env.tick()
        assertEquals(before + 1, fixture.submitted.size)
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun confirmingAfterWheelUsesManualSelectionInsteadOfStationaryHover(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.adapter.activate())
        fixture.aim("ask")
        fixture.adapter.tick()
        env.tick()
        env.tick()
        fixture.packet(env, ClientHeldItemChangePacket(1))
        val view = fixture.view
        fixture.view = view.withSelection("leave", 2, view.renderRevision())
        fixture.targets =
            TargetSnapshot(
                view.sessionId(),
                view.renderRevision(),
                view.nodeVisitId(),
                1,
                fixture.targets.choices(),
                2,
            )
        fixture.adapter.tick()
        env.tick()
        env.tick()
        fixture.submitted.clear()
        fixture.now.set(400_000_000)
        fixture.packet(env, ClientUseItemPacket(PlayerHand.MAIN, 1, 0f, 0f))
        assertEquals(1, fixture.submitted.size)
        val confirm = assertInstanceOf(DialogueInput.Confirm::class.java, fixture.submitted.first())
        assertEquals("leave", confirm.choiceId)
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun deliberateGazeMovementRestoresHoverAfterWheelSelection(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.adapter.activate())
        fixture.aim("ask")
        fixture.adapter.tick()
        env.tick()
        env.tick()
        fixture.packet(env, ClientHeldItemChangePacket(1))
        val view = fixture.view
        fixture.view = view.withSelection("leave", 2, view.renderRevision())
        fixture.targets =
            TargetSnapshot(
                view.sessionId(),
                view.renderRevision(),
                view.nodeVisitId(),
                1,
                fixture.targets.choices(),
                2,
            )
        fixture.submitted.clear()
        fixture.aim("leave")
        fixture.adapter.tick()
        env.tick()
        env.tick()
        fixture.aim("ask")
        fixture.adapter.tick()
        env.tick()
        env.tick()
        assertEquals(1, fixture.submitted.size)
        assertEquals("ask", (fixture.submitted.first() as DialogueInput.Select).choiceId)
        done(env, fixture.adapter.closeAsync())
    }

    @Test
    fun changedHoverCannotConfirmReplacement(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.adapter.activate())
        fixture.aim("leave")
        fixture.now.set(400_000_000)
        fixture.packet(env, ClientUseItemPacket(PlayerHand.MAIN, 1, 0f, 0f))
        assertInstanceOf(DialogueInput.Select::class.java, fixture.submitted.first())
        assertEquals("leave", (fixture.submitted.first() as DialogueInput.Select).choiceId)
        assertTrue(fixture.submitted.none { it is DialogueInput.Confirm })
        done(env, fixture.adapter.closeAsync())
    }

    private fun DialogueView.withSelection(
        selection: String,
        stateRevision: Long,
        renderRevision: Long,
    ): DialogueView =
        DialogueView(
            sessionId(),
            contentPin(),
            nodeId(),
            nodeVisitId(),
            state(),
            stateRevision,
            renderRevision,
            localeTag(),
            speaker(),
            text(),
            choices(),
            selection,
            totalGraphemes(),
            revealedGraphemes(),
            hint(),
            notice(),
            pendingAction(),
        )
}
