package gg.grounds.dialogue.minestom.geometry

import net.minestom.server.collision.BoundingBox
import net.minestom.server.collision.CollisionUtils
import net.minestom.server.coordinate.Point
import net.minestom.server.instance.Instance

/** Uses Minestom's native block collision shapes, including partial blocks. */
object MinestomOcclusion {
    @JvmStatic
    fun visible(instance: Instance?, from: Point, to: Point): Boolean {
        if (instance == null || !instance.isChunkLoaded(from) || !instance.isChunkLoaded(to)) {
            return false
        }
        val delta = to.sub(from).asVec()
        if (!delta.lengthSquared().isFinite() || delta.lengthSquared() > 4096) return false
        return CollisionUtils.handlePhysics(
                instance,
                instance.getChunkAt(from),
                BoundingBox(0.0, 0.0, 0.0),
                from.asPos(),
                delta,
                null,
                false,
            )
            .newPosition()
            .distance(to) < 1e-4
    }
}
