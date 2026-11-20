package gg.grounds.dialogue.minestom

import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueHandle
import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.minestom.geometry.NpcPanelTransform
import gg.grounds.dialogue.minestom.input.InputProfile
import gg.grounds.dialogue.minestom.internal.MinestomDialogueScheduler
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueSurface
import gg.grounds.dialogue.presentation.DialogueTheme
import gg.grounds.dialogue.presentation.LayoutProfile
import gg.grounds.dialogue.presentation.WidgetFrame
import gg.grounds.dialogue.presentation.WidgetFrame.NavigationDirection.NEXT
import gg.grounds.dialogue.presentation.WidgetFrame.NavigationDirection.PREVIOUS
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.math.atan2
import kotlin.math.hypot
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.metadata.display.TextDisplayMeta
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.network.packet.client.play.ClientHeldItemChangePacket
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket
import net.minestom.server.network.packet.server.play.BundlePacket
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import net.minestom.server.network.packet.server.play.SpawnEntityPacket
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@EnvTest
internal class NpcNavigationTest {
    private fun controls() =
        InputProfile(
            setOf(InputProfile.Trigger.USE),
            setOf(InputProfile.Trigger.SWAP_HANDS),
            InputProfile.Trigger.SWAP_HANDS,
            true,
            Duration.ofMillis(50),
        )

    private fun frame(fixture: NativeReviewRegressionTest.F, handle: DialogueHandle): WidgetFrame {
        val surface = DialogueSurface {
            CompletableFuture.failedFuture(UnsupportedOperationException())
        }
        val presenter =
            DialoguePresenter(
                mapOf(
                    "npc" to
                        DialoguePresenter.SurfaceProfile(
                            surface,
                            LayoutProfile.npc(),
                            DialogueTheme.neutral(),
                            fixture.options.assets.metrics(),
                            false,
                        )
                ),
                MinestomDialogueScheduler(fixture.player.instance),
            )
        return presenter.compose(
            handle.snapshot(),
            "npc",
            PresentationPreferences("npc", Duration.ZERO, false, emptyMap()),
        )
    }

    private fun aim(
        env: Env,
        fixture: NativeReviewRegressionTest.F,
        frame: WidgetFrame,
        rect: WidgetFrame.Rect,
    ) {
        val eye = fixture.player.position.add(0.0, fixture.player.eyeHeight, 0.0)
        val height = frame.widgets().first { it.kind == WidgetFrame.Kind.TEXT }.bounds.height
        val hinge =
            frame.widgets().filter { it.kind == WidgetFrame.Kind.CHOICE }.minOf { it.bounds.x }
        val anchor =
            MinestomSpeakerBinding.AnchorSnapshot(
                Vec(0.0, 42.0, 0.0),
                0f,
                Vec(0.0, 43.6, 0.0),
                true,
            )
        val transform =
            NpcPanelTransform.from(anchor, eye).withSpeechHeight(height).withChoiceFold(hinge, 12.0)
        val point =
            transform
                .planeFor(rect)
                .widgetToWorld(rect.x + rect.width / 2, rect.y + rect.height / 2)
        // The native look-at helper misses narrow targets on diagonal rays.
        val delta = point.sub(eye)
        fixture.player.setView(
            Math.toDegrees(atan2(-delta.x(), delta.z())).toFloat(),
            -Math.toDegrees(atan2(delta.y(), hypot(delta.x(), delta.z()))).toFloat(),
        )
        NativeReviewRegressionTest.ticks(env)
    }

    private fun click(env: Env, fixture: NativeReviewRegressionTest.F, sequence: Int) {
        Thread.sleep(60)
        fixture.packet(
            ClientPlayerActionPacket(
                ClientPlayerActionPacket.Status.SWAP_ITEM_HAND,
                Vec.ZERO,
                BlockFace.TOP,
                sequence,
            )
        )
        NativeReviewRegressionTest.ticks(env)
    }

    private fun button(
        frame: WidgetFrame,
        direction: WidgetFrame.NavigationDirection,
    ): WidgetFrame.NavigationButton =
        checkNotNull(frame.scrollbar()).buttons().first { it.direction == direction }

    @Test
    fun arrowsOnlyPageAndDisabledEndCannotConfirmAnAnswer(env: Env) {
        for (wrapped in listOf(false, true)) {
            NativeReviewRegressionTest.F(env, 7, wrapped).use { fixture ->
                fixture.player.setNoGravity(true)
                val ticket = fixture.start("npc", false, controls())
                val handle =
                    (NativeReviewRegressionTest.done(env, ticket.result())
                            as DialogueStartResult.Started)
                        .handle
                assertTrue(
                    displays(fixture).any {
                        text(it).let { value ->
                            value.contains("\ue780") && value.contains("\ue781")
                        }
                    }
                )
                val visit = handle.snapshot().nodeVisitId()
                val state = handle.snapshot().stateRevision()
                for (page in 1..2) {
                    val frame = frame(fixture, handle)
                    val target = button(frame, NEXT).bounds
                    // Hit the generous target outside the smaller painted chevron.
                    aim(env, fixture, frame, WidgetFrame.Rect(target.x + 3, target.y + 3, 2.0, 2.0))
                    click(env, fixture, page)
                    assertEquals(
                        "c0",
                        handle.snapshot().selectedChoiceId(),
                        "Browsing must not select a different answer",
                    )
                    assertEquals(state, handle.snapshot().stateRevision())
                    val visible = "Choice " + page * 3
                    assertTrue(choiceTexts(fixture).any { it.contains(visible) })
                    assertTrue(choiceTexts(fixture).none { it.contains("Choice 0") })
                    assertTrue(choiceScales(fixture).all { it <= 0.320001 })
                    assertEquals(visit, handle.snapshot().nodeVisitId())
                }
                click(env, fixture, 3)
                assertEquals("c0", handle.snapshot().selectedChoiceId())
                assertEquals(visit, handle.snapshot().nodeVisitId())
                val last = frame(fixture, handle)
                val up = button(last, PREVIOUS)
                aim(env, fixture, last, up.bounds)
                click(env, fixture, 4)
                assertEquals("c0", handle.snapshot().selectedChoiceId())
                assertEquals(visit, handle.snapshot().nodeVisitId())
                assertTrue(choiceTexts(fixture).any { it.contains("Choice 3") })
                // Confirm away from the cards while the selected choice is hidden.
                aim(
                    env,
                    fixture,
                    last,
                    last.widgets().first { it.kind == WidgetFrame.Kind.TEXT }.bounds,
                )
                click(env, fixture, 5)
                assertEquals(visit, handle.snapshot().nodeVisitId())
                aim(env, fixture, last, up.bounds)
                click(env, fixture, 6)
                assertTrue(choiceTexts(fixture).any { it.contains("Choice 0") })
                assertTrue(choiceScales(fixture).any { it > 0.33 })
                assertEquals(state, handle.snapshot().stateRevision())
            }
        }
    }

    @Test
    fun wheelAfterBrowsingRevealsSelectionAndFreshHoverCanChooseAnotherAnswer(env: Env) {
        NativeReviewRegressionTest.F(env, 7).use { fixture ->
            fixture.player.setNoGravity(true)
            val ticket = fixture.start("npc", false, controls())
            val handle =
                (NativeReviewRegressionTest.done(env, ticket.result())
                        as DialogueStartResult.Started)
                    .handle
            val first = frame(fixture, handle)
            val down = button(first, NEXT)
            aim(env, fixture, first, down.bounds)
            click(env, fixture, 1)
            assertEquals("c0", handle.snapshot().selectedChoiceId())
            fixture.packet(ClientHeldItemChangePacket(1))
            NativeReviewRegressionTest.ticks(env)
            assertEquals("c1", handle.snapshot().selectedChoiceId())
            assertTrue(choiceTexts(fixture).any { it.contains("Choice 1") })
            aim(env, fixture, first, down.bounds)
            click(env, fixture, 2)
            assertEquals("c1", handle.snapshot().selectedChoiceId())
            // The second visible row now represents c4.
            val row = first.widgets().first { it.choiceId == "c1" }
            aim(env, fixture, first, row.bounds)
            assertEquals("c4", handle.snapshot().selectedChoiceId())
            click(env, fixture, 3)
            assertEquals("reply4", handle.snapshot().nodeId())
        }
    }

    @Test
    fun browsingReusesViewportEntitiesWithoutSpawnDestroyOrPartialMetadata(env: Env) {
        NativeReviewRegressionTest.F(env, 7).use { fixture ->
            fixture.player.setNoGravity(true)
            val ticket = fixture.start("npc", false, controls())
            val handle =
                (NativeReviewRegressionTest.done(env, ticket.result())
                        as DialogueStartResult.Started)
                    .handle
            val first = frame(fixture, handle)
            val down = button(first, NEXT)
            val up = button(first, PREVIOUS)
            val ids = displayIds(fixture)
            val spawns = fixture.connection.trackIncoming(SpawnEntityPacket::class.java)
            val destroys = fixture.connection.trackIncoming(DestroyEntitiesPacket::class.java)
            val metadata = fixture.connection.trackIncoming(EntityMetaDataPacket::class.java)
            val bundles = fixture.connection.trackIncoming(BundlePacket::class.java)
            aim(env, fixture, first, down.bounds)
            click(env, fixture, 1)
            assertEquals(
                ids,
                displayIds(fixture),
                "A full next page must reuse the same physical display slots",
            )
            assertTrue(
                metadata.collect().size <= ids.size,
                "One coherent metadata update per display",
            )
            click(env, fixture, 2)
            assertEquals(
                ids,
                displayIds(fixture),
                "Transparent slots on the partial last page stay allocated",
            )
            assertEquals(1, choiceTexts(fixture).size)
            aim(env, fixture, first, up.bounds)
            click(env, fixture, 3)
            assertEquals(ids, displayIds(fixture))
            assertEquals(3, choiceTexts(fixture).size)
            assertTrue(spawns.collect().isEmpty(), "Scrolling must not respawn UI")
            assertTrue(destroys.collect().isEmpty(), "Scrolling must not tear down UI")
            assertTrue(bundles.collect().size >= 2, "Publish the visual frame together")
            NativeReviewRegressionTest.done(env, ticket.cancel(DialogueEndReason.CANCELLED))
            assertTrue(
                displayIds(fixture).isEmpty(),
                "Cached hidden slots remain owned and are removed on close",
            )
        }
    }

    @Test
    fun wheelPageChangeAndConfirmKeepTheManualSelectionUnderStationaryGaze(env: Env) {
        NativeReviewRegressionTest.F(env, 7).use { fixture ->
            fixture.player.setNoGravity(true)
            val ticket = fixture.start("npc", false, controls())
            val handle =
                (NativeReviewRegressionTest.done(env, ticket.result())
                        as DialogueStartResult.Started)
                    .handle
            val frame = frame(fixture, handle)
            val second = frame.widgets().first { it.choiceId == "c1" }
            aim(env, fixture, frame, second.bounds)
            assertEquals("c1", handle.snapshot().selectedChoiceId())
            fixture.packet(ClientHeldItemChangePacket(1))
            NativeReviewRegressionTest.ticks(env)
            assertEquals("c2", handle.snapshot().selectedChoiceId())
            fixture.packet(ClientHeldItemChangePacket(2))
            NativeReviewRegressionTest.ticks(env)
            assertEquals("c3", handle.snapshot().selectedChoiceId())
            click(env, fixture, 1)
            assertEquals("reply3", handle.snapshot().nodeId())
        }
    }

    private fun displays(fixture: NativeReviewRegressionTest.F) =
        fixture.player.instance.entities.filter { it.entityType == EntityType.TEXT_DISPLAY }

    private fun displayIds(fixture: NativeReviewRegressionTest.F) =
        displays(fixture).mapTo(HashSet()) { it.entityId }

    private fun choiceTexts(fixture: NativeReviewRegressionTest.F) =
        displays(fixture).map(::text).filter { it.contains("Choice ") }

    private fun choiceScales(fixture: NativeReviewRegressionTest.F) =
        displays(fixture)
            .filter { text(it).contains("Choice ") }
            .map { (it.entityMeta as TextDisplayMeta).scale.x() }

    private fun text(entity: net.minestom.server.entity.Entity): String =
        PlainTextComponentSerializer.plainText()
            .serialize((entity.entityMeta as TextDisplayMeta).text)
}
