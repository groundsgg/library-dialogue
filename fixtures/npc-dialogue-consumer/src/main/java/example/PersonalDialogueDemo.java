package example;

import gg.grounds.dialogue.core.port.ActorPort;
import gg.grounds.dialogue.minestom.MinestomSpeakerBinding;
import gg.grounds.dialogue.minestom.render.DialoguePostEffects;
import gg.grounds.npc.core.*;
import gg.grounds.npc.minestom.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

/** Same dialogue application and host, with the published NPC library supplying personal actors. */
public final class PersonalDialogueDemo {
  public static void main(String[] args) throws Exception {
    DialogueDemo.run(
        args, PersonalDialogueDemo::spawn, packAssets(), DialoguePostEffects.unsupported());
  }

  public static Map<String, byte[]> packAssets() throws Exception {
    var files = new TreeMap<String, byte[]>();
    for (var name :
        List.of(
            "items/body.json",
            "items/head.json",
            "models/item/body.json",
            "models/item/head.json")) {
      String path = "assets/npc_demo/" + name;
      try (var in = PersonalDialogueDemo.class.getResourceAsStream("/demo-pack/" + path)) {
        if (in == null) throw new IllegalStateException("Missing original demo resource " + path);
        files.put(path, in.readAllBytes());
      }
    }
    return files;
  }

  public static CompletionStage<DialogueDemo.DemoNpc> spawn(Instance world) {
    var start =
        NpcRenderer.prepare(
            new NpcSpawnRequest(
                world,
                DemoNpcModel.model(),
                new NpcRenderTransform(new NpcTransform(new NpcVector3(0, 40, 0)), null)));
    return start
        .ready()
        .thenCompose(
            result -> {
              if (!(result instanceof NpcPrepareResult.Ready ready))
                return start
                    .cancel()
                    .thenCompose(
                        v ->
                            CompletableFuture.failedFuture(
                                new IllegalStateException(result.toString())));
              var parent = ready.getHandle();
              var closed = new AtomicBoolean();
              var actors =
                  Collections.synchronizedMap(new IdentityHashMap<Player, PersonalNpcActors>());
              FunctionAdapter create = new FunctionAdapter(parent, actors);
              return parent
                  .publish()
                  .thenApply(
                      v ->
                          new DialogueDemo.DemoNpc(
                              new MinestomSpeakerBinding(
                                  "personal-demo-parent-v1",
                                  world,
                                  () -> {
                                    var snapshot = parent.snapshot();
                                    var feet = snapshot.getRoot().getPosition();
                                    var look = snapshot.getLookOrigin();
                                    return new MinestomSpeakerBinding.AnchorSnapshot(
                                        new Vec(feet.getX(), feet.getY(), feet.getZ()),
                                        (float) snapshot.getRoot().getRotationDegrees().getY(),
                                        new Vec(look.getX(), look.getY(), look.getZ()),
                                        !closed.get());
                                  }),
                              create::get,
                              p -> {
                                var actor = actors.get(p);
                                return actor == null
                                    ? CompletableFuture.failedFuture(
                                        new IllegalStateException("Open /dialogue first"))
                                    : actor.wave();
                              },
                              () -> {
                                closed.set(true);
                                return start.cancel();
                              },
                              actors::remove));
            });
  }

  private record FunctionAdapter(NpcHandle parent, Map<Player, PersonalNpcActors> actors) {
    ActorPort get(Player player) {
      return actors.computeIfAbsent(
          player,
          p ->
              new PersonalNpcActors(
                  p,
                  Map.of("mechanic", parent),
                  recipient ->
                      new NpcPresentationOptions(
                          recipient,
                          operation -> {
                            operation.run();
                            return true;
                          },
                          (audience, origin, sound) ->
                              audience.playSound(
                                  Sound.sound(
                                      Key.key(sound.getKey()),
                                      Sound.Source.MASTER,
                                      (float) sound.getVolume(),
                                      (float) sound.getPitch()),
                                  origin.getX(),
                                  origin.getY(),
                                  origin.getZ()))));
    }
  }
}
