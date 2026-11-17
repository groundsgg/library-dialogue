package gg.grounds.dialogue.minestom.grounds;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.kyori.adventure.key.Key;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.common.PostEffectsPacket;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class GroundsPostEffectsTest {
  @Test
  void explicitNativePacketIsSentOnlyToRecipient(Env env) {
    var connection = env.createConnection();
    var other = env.createConnection();
    var world = env.createFlatInstance();
    var p = connection.connect(world, new Pos(0, 42, 0));
    other.connect(world, new Pos(0, 42, 0));
    var packets = connection.trackIncoming(PostEffectsPacket.class);
    var foreign = other.trackIncoming(PostEffectsPacket.class);
    var transport = new GroundsPostEffects();
    assertTrue(transport.supported());
    transport.replace(p, List.of(Key.key("dialogue:vignette"))).toCompletableFuture().join();
    var result = packets.collect();
    assertEquals(1, result.size());
    assertEquals(List.of(Key.key("dialogue:vignette")), result.getFirst().postEffects());
    assertTrue(foreign.collect().isEmpty());
  }
}
