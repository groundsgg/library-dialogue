package gg.grounds.dialogue.minestom.internal;

import gg.grounds.dialogue.minestom.input.TargetSnapshot;
import java.util.Optional;
import net.minestom.server.entity.Player;

/** Internal capability boundary; the core still authorizes every submitted input. */
public interface DialogueInputSource {
  Optional<TargetSnapshot.Target> resolveChat(Player player, long generation, String token);
}
