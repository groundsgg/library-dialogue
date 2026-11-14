package gg.grounds.dialogue.minestom.geometry;

import net.minestom.server.collision.*;
import net.minestom.server.coordinate.*;
import net.minestom.server.instance.Instance;

/** Uses Minestom's native block collision shapes, including partial blocks. */
public final class MinestomOcclusion {
  private MinestomOcclusion() {}

  public static boolean visible(Instance instance, Point from, Point to) {
    if (instance == null || !instance.isChunkLoaded(from) || !instance.isChunkLoaded(to))
      return false;
    var delta = to.sub(from).asVec();
    if (!Double.isFinite(delta.lengthSquared()) || delta.lengthSquared() > 4096) return false;
    return CollisionUtils.handlePhysics(
                instance,
                instance.getChunkAt(from),
                new BoundingBox(0, 0, 0),
                from.asPos(),
                delta,
                null,
                false)
            .newPosition()
            .distance(to)
        < 1e-4;
  }
}
