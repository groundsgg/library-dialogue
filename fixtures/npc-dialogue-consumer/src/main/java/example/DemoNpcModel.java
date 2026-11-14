package example;

import gg.grounds.npc.core.*;
import java.util.*;

/** Original neutral library-npc example model; MIT, shared by the same project owner. */
public final class DemoNpcModel {
  public static NpcModel model() {
    var zero = NpcVector3.ZERO;
    var head = new NpcVector3(0, 1.4, 0);
    var wave =
        new NpcClip(
            "once",
            1,
            Map.of(
                "head",
                Map.of(
                    "rotation",
                    List.of(
                        new NpcKeyframe(0, zero, "linear"),
                        new NpcKeyframe(0.5, new NpcVector3(0, 0, 20), "linear"),
                        new NpcKeyframe(1, zero, "linear")))));
    return new NpcModel(
        "npc_demo:bot",
        new NpcBounds(new NpcVector3(0, 1, 0), new NpcVector3(1, 2, 1)),
        List.of(new NpcBone("body", null, zero, zero), new NpcBone("head", "body", head, zero)),
        List.of(
            new NpcPart(
                "body",
                "npc_demo:body",
                new NpcVector3(0, 0.7, 0),
                zero,
                zero,
                new NpcVector3(0.7, 1.2, 0.4)),
            new NpcPart(
                "head",
                "npc_demo:head",
                new NpcVector3(0, 1.6, 0),
                head,
                zero,
                new NpcVector3(0.5, 0.5, 0.5))),
        Map.of("idle", new NpcClip("loop", 1, Map.of()), "wave", wave),
        Map.of("wave", new NpcClipSound("minecraft:entity.villager.yes", 0.8, 1.0, 12)));
  }
}
