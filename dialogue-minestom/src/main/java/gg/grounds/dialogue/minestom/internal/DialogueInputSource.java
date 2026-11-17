package gg.grounds.dialogue.minestom.internal;

import gg.grounds.dialogue.minestom.input.TargetSnapshot;
import java.util.Optional;
import net.minestom.server.entity.Player;

/** Core-authorized semantic inputs and separate presentation-only browsing capability. */
public interface DialogueInputSource {
  default String effectiveSurface() {
    return "";
  }

  default boolean hiddenHotbar() {
    return false;
  }

  default void refreshOutput() {}

  default java.util.concurrent.CompletionStage<gg.grounds.dialogue.core.runtime.InputResult> browse(
      TargetSnapshot displayed, int start) {
    return java.util.concurrent.CompletableFuture.completedFuture(
        gg.grounds.dialogue.core.runtime.InputResult.STALE);
  }

  default TargetSnapshot targets() {
    return null;
  }

  default gg.grounds.dialogue.minestom.render.NpcDialogueSurface.Geometry npcGeometry() {
    return null;
  }

  Optional<TargetSnapshot.Target> resolveChat(Player player, long generation, String token);
}
