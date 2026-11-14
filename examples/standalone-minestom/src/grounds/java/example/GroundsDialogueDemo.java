package example;

import gg.grounds.dialogue.minestom.render.DialoguePostEffects;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.key.Key;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.common.PostEffectsPacket;

/** Opt-in application transport, compiled only against the pinned Grounds fork. */
public final class GroundsDialogueDemo {
  public static DialoguePostEffects effects() {
    return new DialoguePostEffects() {
      public boolean supported() {
        return true;
      }

      public CompletionStage<Void> replace(Player player, List<Key> keys) {
        player.sendPacket(new PostEffectsPacket(List.copyOf(keys)));
        return CompletableFuture.completedFuture(null);
      }
    };
  }

  public static void main(String[] args) throws Exception {
    System.setProperty("dialogue.profile", "grounds-26.3");
    DialogueDemo.run(args, DialogueDemo::nativeAnchor, Map.of(), effects());
  }
}
