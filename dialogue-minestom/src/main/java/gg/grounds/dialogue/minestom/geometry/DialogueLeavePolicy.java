package gg.grounds.dialogue.minestom.geometry;

import gg.grounds.dialogue.core.definition.DialogueDefinition.Interaction;
import gg.grounds.dialogue.core.runtime.DialogueEndReason;
import java.time.Duration;
import java.util.*;

/**
 * Independent distance and look timers with angle hysteresis. Clock zero is a valid first sample.
 */
public final class DialogueLeavePolicy {
  private final Interaction interaction;
  private final long distanceDelay, lookDelay;
  private Long awaySince, farSince;

  public DialogueLeavePolicy(Interaction interaction, Duration distanceGrace) {
    this.interaction = Objects.requireNonNull(interaction);
    distanceDelay = distanceGrace.toNanos();
    lookDelay = Duration.ofMillis(interaction.lookAway().exitDelayMs()).toNanos();
    if (distanceDelay < 0
        || lookDelay < 0
        || !Double.isFinite(interaction.maxDistanceBlocks())
        || interaction.maxDistanceBlocks() <= 0)
      throw new IllegalArgumentException("Invalid leave policy");
  }

  public Optional<DialogueEndReason> observe(
      double angle, double distance, boolean onVisibleAnswer, long now) {
    if (!Double.isFinite(angle)
        || !Double.isFinite(distance)
        || angle < 0
        || angle > 180
        || distance < 0) return Optional.of(DialogueEndReason.HOST_CHANGED);
    if (distance > interaction.maxDistanceBlocks()) {
      if (farSince == null) farSince = now;
      if (now - farSince >= distanceDelay) return Optional.of(DialogueEndReason.OUT_OF_RANGE);
    } else farSince = null;
    var look = interaction.lookAway();
    if (!look.enabled() || onVisibleAnswer || angle < look.resetAngleDegrees()) awaySince = null;
    else if (angle > look.exitAngleDegrees() && awaySince == null) awaySince = now;
    if (awaySince != null && now - awaySince >= lookDelay)
      return Optional.of(DialogueEndReason.LOOKED_AWAY);
    return Optional.empty();
  }
}
