package gg.grounds.dialogue.minestom.channel;

import gg.grounds.dialogue.core.port.OwnedResource;
import java.util.List;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;

/** Inject the application's shared coordinator when other plugins use the same native channels. */
public interface OutputChannels {
  interface Lease<T> extends OwnedResource {
    CompletionStage<Void> replace(T value);
  }

  interface HudLease extends Lease<Component> {
    /** Coordinators that suppress a registered source must report it to its surface. */
    default boolean visible() {
      return true;
    }
  }

  interface EffectLease extends Lease<List<Key>> {}

  HudLease acquireHud(Player player, long generation, int priority);

  EffectLease acquireEffects(Player player, long generation);

  boolean supportedEffects();
}
