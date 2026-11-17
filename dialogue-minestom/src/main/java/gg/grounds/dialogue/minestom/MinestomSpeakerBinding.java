package gg.grounds.dialogue.minestom;

import java.util.*;
import java.util.function.Supplier;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Instance;

/** Borrowed body anchor. The host never owns the shared NPC or its collision entity. */
public record MinestomSpeakerBinding(
    String generation, Instance instance, Supplier<AnchorSnapshot> snapshot) {
  public MinestomSpeakerBinding {
    Objects.requireNonNull(generation);
    Objects.requireNonNull(instance);
    Objects.requireNonNull(snapshot);
    if (generation.isBlank()) throw new IllegalArgumentException("generation");
  }

  public record AnchorSnapshot(Point feet, float bodyYaw, Point lookOrigin, boolean valid) {
    public AnchorSnapshot {
      Objects.requireNonNull(feet);
      Objects.requireNonNull(lookOrigin);
      if (!Double.isFinite(feet.x())
          || !Double.isFinite(feet.y())
          || !Double.isFinite(feet.z())
          || !Float.isFinite(bodyYaw)
          || !Double.isFinite(lookOrigin.x())
          || !Double.isFinite(lookOrigin.y())
          || !Double.isFinite(lookOrigin.z())) throw new IllegalArgumentException("INVALID_ANCHOR");
    }
  }
}
