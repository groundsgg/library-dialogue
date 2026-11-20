package gg.grounds.dialogue.minestom.geometry

import gg.grounds.dialogue.minestom.MinestomSpeakerBinding.AnchorSnapshot
import gg.grounds.dialogue.minestom.input.TargetSnapshot
import gg.grounds.dialogue.presentation.WidgetFrame
import java.util.UUID
import net.minestom.server.coordinate.Vec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NpcHitTestTest {
    private val anchor = AnchorSnapshot(Vec.ZERO, 0f, Vec(0.0, 1.6, 0.0), true)

    @Test
    fun frontBoundedYawKeepsWorldSize() {
        val transform = NpcPanelTransform.from(anchor, Vec(-10.0, 2.0, 0.0))
        assertEquals(15f, transform.bodyfrontYaw, 1e-6f)
        assertEquals(.32, transform.scale(), 1e-9)
        for (eye in listOf(Vec(0.0, 2.0, 2.0), Vec(50.0, 2.0, 20.0))) {
            val current = NpcPanelTransform.from(anchor, eye)
            val world = current.widgetToWorld(123.0, 45.0)
            val gui = current.worldToWidget(world)
            assertEquals(123.0, gui.x(), 1e-6)
            assertEquals(45.0, gui.y(), 1e-6)
            assertEquals(
                320 * .008,
                current.widgetToWorld(0.0, 0.0).distance(current.widgetToWorld(320.0, 0.0)),
                1e-6,
            )
        }
    }

    @Test
    fun hitUsesUnscaledBounds() {
        val eye = Vec(0.0, 2.0, 4.0)
        val transform = NpcPanelTransform.from(anchor, eye)
        val rect = WidgetFrame.Rect(326.0, 0.0, 240.0, 40.0)
        val target = TargetSnapshot(UUID.randomUUID(), 1, 1, 1, mapOf("yes" to rect))
        val ray = transform.widgetToWorld(400.0, 20.0).sub(eye).asVec().normalize()
        assertEquals(
            "yes",
            NpcHitTest.hit(eye, ray, transform, target) { _, _ -> true }.orElseThrow(),
        )
        val outside = transform.widgetToWorld(570.0, 20.0).sub(eye).asVec().normalize()
        assertTrue(NpcHitTest.hit(eye, outside, transform, target) { _, _ -> true }.isEmpty)
    }

    @Test
    fun backAndOccludedTargetsCannotHover() {
        val eye = Vec(0.0, 2.0, 4.0)
        val transform = NpcPanelTransform.from(anchor, eye)
        val rect = WidgetFrame.Rect(0.0, 0.0, 320.0, 40.0)
        val target = TargetSnapshot(UUID.randomUUID(), 1, 1, 1, mapOf("yes" to rect))
        val point = transform.widgetToWorld(160.0, 20.0)
        val back = point.sub(transform.normal.mul(3.0))
        assertTrue(
            NpcHitTest.hit(back, transform.normal, transform, target) { _, _ -> true }.isEmpty
        )
        assertTrue(
            NpcHitTest.hit(eye, point.sub(eye).asVec().normalize(), transform, target) { _, _ ->
                    false
                }
                .isEmpty
        )
    }

    @Test
    fun foldedChoicePlaneKeepsTheHingeAndWorldSizeAndUsesItsActualFront() {
        val eye = Vec(0.0, 2.0, 4.0)
        val flat = NpcPanelTransform.from(anchor, eye)
        val folded = flat.withChoiceFold(342.0, 30.0)
        val bounds = WidgetFrame.Rect(342.0, 0.0, 240.0, 40.0)
        assertEquals(
            0.0,
            flat.widgetToWorld(342.0, 20.0).distance(folded.widgetToWorld(342.0, 20.0)),
            1e-9,
        )
        assertEquals(
            240 * .008,
            folded.widgetToWorld(342.0, 20.0).distance(folded.widgetToWorld(582.0, 20.0)),
            1e-9,
        )
        assertEquals(
            240 * .008 * .5,
            folded.widgetToWorld(582.0, 20.0).z() - flat.widgetToWorld(342.0, 20.0).z(),
            1e-9,
        )
        assertEquals(flat.widgetToWorld(160.0, 20.0), folded.widgetToWorld(160.0, 20.0))
        val plane = folded.planeFor(bounds)
        val center = folded.widgetToWorld(462.0, 20.0)
        val ray = center.sub(eye).asVec().normalize()
        val targets = TargetSnapshot(UUID.randomUUID(), 1, 1, 1, mapOf("answer" to bounds))
        assertEquals(
            "answer",
            NpcHitTest.hit(eye, ray, folded, targets) { _, b -> b.distance(center) < 1e-6 }
                .orElseThrow(),
        )
        assertTrue(NpcHitTest.inDialogueZone(eye, ray, folded, listOf(bounds)) { _, _ -> true })
        assertTrue(NpcHitTest.hit(eye, ray, folded, targets) { _, _ -> false }.isEmpty)
        assertTrue(
            NpcHitTest.hit(center.sub(plane.normal.mul(2.0)), plane.normal, folded, targets) { _, _
                    ->
                    true
                }
                .isEmpty
        )
        val outside = folded.widgetToWorld(583.0, 20.0).sub(eye).asVec().normalize()
        assertTrue(NpcHitTest.hit(eye, outside, folded, targets) { _, _ -> true }.isEmpty)
        assertEquals(
            flat.widgetToWorld(462.0, 20.0),
            flat.withChoiceFold(342.0, 0.0).widgetToWorld(462.0, 20.0),
        )
    }

    @Test
    fun navigationHitConsumesDisabledBackAndOccludedButtonsWithoutActivation() {
        val eye = Vec(0.0, 2.0, 4.0)
        val transform =
            NpcPanelTransform.from(anchor, eye).withSpeechHeight(40.0).withChoiceFold(342.0, 12.0)
        val rect = WidgetFrame.Rect(593.0, 136.0, 16.0, 16.0)
        val button =
            WidgetFrame.NavigationButton(WidgetFrame.NavigationDirection.NEXT, rect, "next")
        val targets =
            TargetSnapshot(
                UUID.randomUUID(),
                1,
                1,
                1,
                emptyMap(),
                1,
                mapOf(button.direction to button),
            )
        val point = transform.widgetToWorld(601.0, 144.0)
        val ray = point.sub(eye).asVec().normalize()
        assertTrue(
            NpcHitTest.navigationHit(eye, ray, transform, targets) { _, _ -> true }
                .orElseThrow()
                .usable
        )
        assertFalse(
            NpcHitTest.navigationHit(eye, ray, transform, targets) { _, _ -> false }
                .orElseThrow()
                .usable
        )
        val plane = transform.planeFor(rect)
        assertFalse(
            NpcHitTest.navigationHit(
                    point.sub(plane.normal.mul(2.0)),
                    plane.normal,
                    transform,
                    targets,
                ) { _, _ ->
                    true
                }
                .orElseThrow()
                .usable
        )
        val disabled = WidgetFrame.NavigationButton(button.direction, rect, null)
        val noNext =
            TargetSnapshot(
                targets.sessionId(),
                1,
                1,
                1,
                emptyMap(),
                1,
                mapOf(button.direction to disabled),
            )
        assertFalse(
            NpcHitTest.navigationHit(eye, ray, transform, noNext) { _, _ -> true }
                .orElseThrow()
                .usable
        )
    }

    @Test
    fun disabledFrontProtectsZoneButNeverSelects() {
        val eye = Vec(0.0, 2.0, 4.0)
        val transform = NpcPanelTransform.from(anchor, eye)
        val rect = WidgetFrame.Rect(326.0, 0.0, 240.0, 40.0)
        val ray = transform.widgetToWorld(400.0, 20.0).sub(eye).asVec().normalize()
        val targets = TargetSnapshot(UUID.randomUUID(), 1, 1, 1, emptyMap())
        assertTrue(NpcHitTest.hit(eye, ray, transform, targets) { _, _ -> true }.isEmpty)
        assertTrue(NpcHitTest.inDialogueZone(eye, ray, transform, listOf(rect)) { _, _ -> true })
        assertFalse(NpcHitTest.inDialogueZone(eye, ray, transform, listOf(rect)) { _, _ -> false })
    }
}
