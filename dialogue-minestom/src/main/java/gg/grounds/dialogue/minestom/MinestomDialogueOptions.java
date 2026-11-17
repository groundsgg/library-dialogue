package gg.grounds.dialogue.minestom;

import gg.grounds.dialogue.core.runtime.RuntimeOptions;
import gg.grounds.dialogue.minestom.asset.*;
import gg.grounds.dialogue.minestom.channel.OutputChannels;
import gg.grounds.dialogue.presentation.UiLabelSource;
import java.time.Duration;
import java.util.Objects;

public record MinestomDialogueOptions(
    RuntimeOptions.Budgets budgets,
    MinecraftPackProfile profile,
    AssetBundle assets,
    OutputChannels channels,
    UiLabelSource labels,
    Duration distanceGrace) {
  public MinestomDialogueOptions {
    Objects.requireNonNull(budgets);
    Objects.requireNonNull(profile);
    Objects.requireNonNull(assets);
    Objects.requireNonNull(channels);
    Objects.requireNonNull(labels);
    Objects.requireNonNull(distanceGrace);
    if (!assets.profile().equals(profile)
        || distanceGrace.isNegative()
        || distanceGrace.compareTo(Duration.ofSeconds(60)) > 0)
      throw new IllegalArgumentException("Invalid minestom dialogue options");
  }
}
