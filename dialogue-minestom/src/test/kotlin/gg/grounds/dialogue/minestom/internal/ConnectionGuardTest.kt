package gg.grounds.dialogue.minestom.internal

import net.minestom.server.coordinate.Pos
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@EnvTest
internal class ConnectionGuardTest {
    @Test
    fun invalidationImmediatelyRejectsOldGeneration(env: Env) {
        val instance = env.createFlatInstance()
        val player = env.createConnection().connect(instance, Pos(0.0, 42.0, 0.0))
        val guard = ConnectionGuard(player, 1, instance)
        assertTrue(guard.isCurrent())
        guard.invalidate()
        assertFalse(guard.isCurrent())
        assertTrue(ConnectionGuard(player, 2, instance).isCurrent())
    }

    @Test
    fun instanceChangeInvalidatesBoundGuard(env: Env) {
        val instance = env.createFlatInstance()
        val player = env.createConnection().connect(instance, Pos(0.0, 42.0, 0.0))
        val guard = ConnectionGuard(player, 1, instance)
        player.setInstance(env.createFlatInstance(), Pos(0.0, 42.0, 0.0)).join()
        assertFalse(guard.isCurrent())
    }
}
