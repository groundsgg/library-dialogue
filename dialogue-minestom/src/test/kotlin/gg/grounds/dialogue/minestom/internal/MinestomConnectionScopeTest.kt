package gg.grounds.dialogue.minestom.internal

import gg.grounds.dialogue.minestom.MinestomConnectionScope
import net.minestom.server.coordinate.Pos
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@EnvTest
internal class MinestomConnectionScopeTest {
    @Test
    fun identityAndCleanupPredicatesRemainDistinct(env: Env) {
        val instance = env.createFlatInstance()
        val player = env.createConnection().connect(instance, Pos(0.0, 42.0, 0.0))
        val other = env.createConnection().connect(instance, Pos(0.0, 42.0, 0.0))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            MinestomConnectionScope(player, -1, instance)
        }
        val scope = MinestomConnectionScope(player, 0, instance)
        org.junit.jupiter.api.Assertions.assertSame(player, scope.player)
        org.junit.jupiter.api.Assertions.assertSame(instance, scope.instance)
        assertTrue(scope.matches(player, 0))
        assertFalse(scope.matches(other, 0))
        assertFalse(scope.matches(player, 1))
        scope.invalidate()
        scope.invalidate()
        assertFalse(scope.matches(player, 0))
        assertTrue(scope.hasCurrentConnection())
        player.setInstance(env.createFlatInstance(), Pos(0.0, 42.0, 0.0)).join()
        assertFalse(scope.hasCurrentConnection())
    }

    @Test
    fun invalidationImmediatelyRejectsOldGeneration(env: Env) {
        val instance = env.createFlatInstance()
        val player = env.createConnection().connect(instance, Pos(0.0, 42.0, 0.0))
        val guard = MinestomConnectionScope(player, 1, instance)
        assertTrue(guard.isCurrent())
        guard.invalidate()
        assertFalse(guard.isCurrent())
        assertTrue(MinestomConnectionScope(player, 2, instance).isCurrent())
    }

    @Test
    fun instanceChangeInvalidatesBoundGuard(env: Env) {
        val instance = env.createFlatInstance()
        val player = env.createConnection().connect(instance, Pos(0.0, 42.0, 0.0))
        val guard = MinestomConnectionScope(player, 1, instance)
        player.setInstance(env.createFlatInstance(), Pos(0.0, 42.0, 0.0)).join()
        assertFalse(guard.isCurrent())
    }
}
