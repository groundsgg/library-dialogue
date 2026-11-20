package gg.grounds.dialogue.minestom.geometry

import gg.grounds.dialogue.minestom.MinestomSpeakerBinding.AnchorSnapshot
import gg.grounds.dialogue.presentation.WidgetFrame.Rect
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec

/** Fixed world scale, upright body-front plane and optional hinged answer column. */
@JvmRecord
data class NpcPanelTransform(
    val origin: Point,
    val right: Vec,
    val normal: Vec,
    val bodyfrontYaw: Float,
    val speechHeight: Double,
    val fold: Fold?,
) {
    @JvmRecord
    data class Fold(val pivot: Double, val degrees: Double) {
        init {
            if (!pivot.isFinite() || !degrees.isFinite() || kotlin.math.abs(degrees) > 45) {
                throw IllegalArgumentException("Invalid panel fold")
            }
        }
    }

    constructor(
        origin: Point,
        right: Vec,
        normal: Vec,
        bodyfrontYaw: Float,
        speechHeight: Double,
    ) : this(origin, right, normal, bodyfrontYaw, speechHeight, null)

    fun scale(): Double = .32

    fun withSpeechHeight(height: Double): NpcPanelTransform =
        NpcPanelTransform(origin, right, normal, bodyfrontYaw, height, fold)

    fun withFold(value: Fold?): NpcPanelTransform =
        NpcPanelTransform(origin, right, normal, bodyfrontYaw, speechHeight, value)

    fun withChoiceFold(pivot: Double, degrees: Double): NpcPanelTransform {
        val value = Fold(pivot, degrees)
        return withFold(if (degrees == 0.0) null else value)
    }

    fun planeFor(bounds: Rect): NpcPanelTransform =
        if (fold != null && bounds.x + bounds.width / 2 >= fold.pivot) choicePlane() else this

    private fun choicePlane(): NpcPanelTransform {
        val currentFold = requireNotNull(fold)
        val radians = Math.toRadians(currentFold.degrees)
        val cosine = kotlin.math.cos(radians)
        val sine = kotlin.math.sin(radians)
        val foldedRight = right.mul(cosine).add(normal.mul(sine))
        val foldedNormal = normal.mul(cosine).sub(right.mul(sine))
        val hinge = flatPoint(currentFold.pivot, speechHeight)
        return NpcPanelTransform(
            hinge.sub(foldedRight.mul((currentFold.pivot - 160) * UNIT)),
            foldedRight,
            foldedNormal,
            (bodyfrontYaw + currentFold.degrees).toFloat(),
            speechHeight,
        )
    }

    private fun flatPoint(x: Double, y: Double): Point =
        origin.add(right.mul((x - 160) * UNIT)).add(0.0, (speechHeight - y) * UNIT, 0.0)

    fun widgetToWorld(x: Double, y: Double): Point =
        if (fold != null && x >= fold.pivot) choicePlane().flatPoint(x, y) else flatPoint(x, y)

    fun worldToWidget(point: Point): Point {
        val delta = point.sub(origin).asVec()
        val x = delta.dot(right) / UNIT + 160
        if (fold != null && x >= fold.pivot) return choicePlane().worldToWidget(point)
        return Vec(x, speechHeight - delta.y() / UNIT, delta.dot(normal))
    }

    fun position(): Pos = Pos(origin, bodyfrontYaw, 0.0f)

    companion object {
        const val UNIT: Double = .025 * .32

        @JvmStatic
        fun from(anchor: AnchorSnapshot, eye: Point): NpcPanelTransform {
            if (!anchor.valid) throw IllegalArgumentException("Invalid anchor")
            val origin = anchor.feet.add(0.0, 1.9, 0.0)
            val toward = eye.sub(origin)
            val bearing =
                if (Math.hypot(toward.x(), toward.z()) < 1e-9) anchor.bodyYaw.toDouble()
                else Math.toDegrees(Math.atan2(-toward.x(), toward.z()))
            val offset =
                Math.clamp(Math.IEEEremainder(bearing - anchor.bodyYaw, 360.0) * .25, -15.0, 15.0)
            val yaw = (anchor.bodyYaw + offset).toFloat()
            val angle = Math.toRadians(yaw.toDouble())
            return NpcPanelTransform(
                origin,
                Vec(kotlin.math.cos(angle), 0.0, kotlin.math.sin(angle)),
                Vec(-kotlin.math.sin(angle), 0.0, kotlin.math.cos(angle)),
                yaw,
                72.0,
            )
        }
    }
}
