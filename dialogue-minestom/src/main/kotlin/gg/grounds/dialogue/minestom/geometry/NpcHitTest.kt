package gg.grounds.dialogue.minestom.geometry

import gg.grounds.dialogue.minestom.input.TargetSnapshot
import gg.grounds.dialogue.presentation.WidgetFrame
import java.util.Optional
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Vec

object NpcHitTest {
    fun interface OcclusionTest {
        fun visible(from: Point, to: Point): Boolean
    }

    @JvmRecord
    data class NavigationHit(val button: WidgetFrame.NavigationButton, val usable: Boolean)

    @JvmRecord data class ChoiceHit(val choiceId: String, val usable: Boolean)

    private fun intersection(
        eye: Point,
        direction: Vec,
        transform: NpcPanelTransform,
        bounds: WidgetFrame.Rect,
        frontOnly: Boolean = true,
    ): Point? {
        val plane = transform.planeFor(bounds)
        val denominator = direction.dot(plane.normal)
        if (kotlin.math.abs(denominator) < 1e-9 || (frontOnly && denominator >= -1e-9)) {
            return null
        }
        val distance = plane.origin.sub(eye).asVec().dot(plane.normal) / denominator
        if (!distance.isFinite() || distance <= 0) return null
        val world = eye.add(direction.mul(distance))
        val point = plane.worldToWidget(world)
        return if (bounds.contains(point.x(), point.y())) world else null
    }

    /** A blocked/disabled/back button still consumes confirmation; it never confirms an answer. */
    @JvmStatic
    fun navigationHit(
        eye: Point,
        direction: Vec,
        transform: NpcPanelTransform,
        targets: TargetSnapshot,
        occlusion: OcclusionTest,
    ): Optional<NavigationHit> {
        for (button in targets.navigation().values) {
            val world = intersection(eye, direction, transform, button.bounds, false) ?: continue
            val front = direction.dot(transform.planeFor(button.bounds).normal) < -1e-9
            return Optional.of(
                NavigationHit(button, front && button.enabled() && occlusion.visible(eye, world))
            )
        }
        return Optional.empty()
    }

    /**
     * A blocked, unavailable or back-facing card consumes confirmation without choosing another.
     */
    @JvmStatic
    fun choiceHit(
        eye: Point,
        direction: Vec,
        transform: NpcPanelTransform,
        choiceBounds: Map<String, WidgetFrame.Rect>,
        targets: TargetSnapshot,
        occlusion: OcclusionTest,
    ): Optional<ChoiceHit> {
        for ((choiceId, bounds) in choiceBounds) {
            val world = intersection(eye, direction, transform, bounds, false) ?: continue
            val front = direction.dot(transform.planeFor(bounds).normal) < -1e-9
            return Optional.of(
                ChoiceHit(
                    choiceId,
                    front &&
                        targets.choices().containsKey(choiceId) &&
                        occlusion.visible(eye, world),
                )
            )
        }
        return Optional.empty()
    }

    @JvmStatic
    fun hit(
        eye: Point,
        direction: Vec,
        transform: NpcPanelTransform,
        targets: TargetSnapshot,
        occlusion: OcclusionTest,
    ): Optional<String> {
        for ((choiceId, bounds) in targets.choices()) {
            val world = intersection(eye, direction, transform, bounds)
            if (world != null && occlusion.visible(eye, world)) return Optional.of(choiceId)
        }
        return Optional.empty()
    }

    @JvmStatic
    fun inDialogueZone(
        eye: Point,
        direction: Vec,
        transform: NpcPanelTransform,
        fronts: List<WidgetFrame.Rect>,
        occlusion: OcclusionTest,
    ): Boolean {
        for (bounds in fronts) {
            val world = intersection(eye, direction, transform, bounds)
            if (world != null && occlusion.visible(eye, world)) return true
        }
        return false
    }
}
