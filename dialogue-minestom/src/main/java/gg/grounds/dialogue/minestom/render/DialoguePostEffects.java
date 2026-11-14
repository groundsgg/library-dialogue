package gg.grounds.dialogue.minestom.render;

import java.util.List;
import java.util.concurrent.*;
import net.kyori.adventure.key.Key;
import net.minestom.server.entity.Player;

/** Explicit transport capability; the public 26.2 profile has no native post-effect packet. */
public interface DialoguePostEffects {
  boolean supported();

  CompletionStage<Void> replace(Player player, List<Key> effects);

  static DialoguePostEffects unsupported() {
    return new DialoguePostEffects() {
      public boolean supported() {
        return false;
      }

      public CompletionStage<Void> replace(Player p, List<Key> keys) {
        return CompletableFuture.failedFuture(
            new UnsupportedOperationException("POST_EFFECT_UNSUPPORTED"));
      }
    };
  }
}
