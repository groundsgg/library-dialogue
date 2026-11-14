package gg.grounds.dialogue.minestom.grounds;

import gg.grounds.dialogue.minestom.render.DialoguePostEffects;
import java.util.List;
import java.util.concurrent.*;
import net.kyori.adventure.key.Key;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.common.PostEffectsPacket;

/** Compile only against the explicitly selected Grounds 26.3 profile. */
public final class GroundsPostEffects implements DialoguePostEffects {
  public boolean supported() {
    return true;
  }

  public CompletionStage<Void> replace(Player player, List<Key> effects) {
    player.sendPacket(new PostEffectsPacket(List.copyOf(effects)));
    return CompletableFuture.completedFuture(null);
  }
}
