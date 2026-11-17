package gg.grounds.dialogue.minestom.input;

import java.time.Duration;
import java.util.*;

/** Minecraft actions, independent of the physical key chosen by the player. */
public record InputProfile(
    Set<Trigger> open,
    Set<Trigger> confirm,
    Trigger primaryConfirm,
    boolean scroll,
    Duration quietPeriod) {
  public enum Trigger {
    ATTACK,
    USE,
    SWAP_HANDS
  }

  public InputProfile {
    open = Set.copyOf(open);
    confirm = Set.copyOf(confirm);
    Objects.requireNonNull(primaryConfirm);
    Objects.requireNonNull(quietPeriod);
    if (open.isEmpty()
        || open.contains(Trigger.SWAP_HANDS)
        || confirm.isEmpty()
        || !confirm.contains(primaryConfirm)
        || quietPeriod.compareTo(Duration.ofMillis(50)) < 0
        || quietPeriod.compareTo(Duration.ofSeconds(2)) > 0)
      throw new IllegalArgumentException("Invalid controls");
  }

  public static InputProfile defaults() {
    return new InputProfile(
        Set.of(Trigger.USE),
        Set.of(Trigger.USE, Trigger.SWAP_HANDS),
        Trigger.USE,
        true,
        Duration.ofMillis(350));
  }
}
