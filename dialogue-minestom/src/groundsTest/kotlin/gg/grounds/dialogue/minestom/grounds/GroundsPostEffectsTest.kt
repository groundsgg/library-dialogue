package gg.grounds.dialogue.minestom.grounds

import net.kyori.adventure.key.Key
import net.minestom.server.coordinate.Pos
import net.minestom.server.network.packet.server.common.PostEffectsPacket
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@EnvTest
class GroundsPostEffectsTest {
    @Test
    fun explicitNativePacketIsSentOnlyToRecipient(env: Env) {
        val connection = env.createConnection()
        val other = env.createConnection()
        val world = env.createFlatInstance()
        val player = connection.connect(world, Pos(0.0, 42.0, 0.0))
        other.connect(world, Pos(0.0, 42.0, 0.0))
        val packets = connection.trackIncoming(PostEffectsPacket::class.java)
        val foreign = other.trackIncoming(PostEffectsPacket::class.java)
        val transport = GroundsPostEffects()
        assertTrue(transport.supported())
        transport.replace(player, listOf(Key.key("dialogue:vignette"))).toCompletableFuture().join()
        val result = packets.collect()
        assertEquals(1, result.size)
        assertEquals(listOf(Key.key("dialogue:vignette")), result.first().postEffects())
        assertTrue(foreign.collect().isEmpty())
    }
}
