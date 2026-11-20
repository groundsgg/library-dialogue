package gg.grounds.dialogue.minestom.asset

import java.util.UUID
import net.kyori.adventure.resource.ResourcePackStatus
import net.minestom.server.coordinate.Pos
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@EnvTest
class PackStateTrackerTest {
    @Test
    fun retiredPackUuidCannotBeRegisteredAgain(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val tracker = PackStateTracker()
        val old = UUID.randomUUID()
        val middle = UUID.randomUUID()
        tracker.expect(player, 1, old, "old")
        tracker.expect(player, 1, middle, "middle")
        assertThrows(IllegalArgumentException::class.java) {
            tracker.expect(player, 2, old, "new")
        }
        tracker.observe(player, old, ResourcePackStatus.SUCCESSFULLY_LOADED)
        assertFalse(tracker.isReady(player, 1, "middle"))
        tracker.expect(player, 1, middle, "middle")
        tracker.observe(player, middle, ResourcePackStatus.SUCCESSFULLY_LOADED)
        assertTrue(tracker.isReady(player, 1, "middle"))
    }

    @Test
    fun wrongPackAndOldConnectionNeverEnableGraphics(env: Env) {
        val first = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val second = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val tracker = PackStateTracker()
        val id = UUID.randomUUID()
        val other = UUID.randomUUID()
        tracker.expect(first, 1, id, "pin")
        tracker.observe(first, 1, other, "pin", ResourcePackStatus.SUCCESSFULLY_LOADED)
        assertFalse(tracker.isReady(first, 1, "pin"))
        tracker.observe(second, 1, id, "pin", ResourcePackStatus.SUCCESSFULLY_LOADED)
        assertFalse(tracker.isReady(first, 1, "pin"))
        tracker.observe(first, 1, id, "wrong", ResourcePackStatus.SUCCESSFULLY_LOADED)
        assertFalse(tracker.isReady(first, 1, "pin"))
        tracker.observe(first, 1, id, "pin", ResourcePackStatus.SUCCESSFULLY_LOADED)
        assertTrue(tracker.isReady(first, 1, "pin"))
        tracker.expect(first, 2, other, "pin")
        tracker.observe(first, 1, id, "pin", ResourcePackStatus.SUCCESSFULLY_LOADED)
        assertFalse(tracker.isReady(first, 2, "pin"))
        tracker.observe(first, 2, other, "pin", ResourcePackStatus.SUCCESSFULLY_LOADED)
        assertTrue(tracker.isReady(first, 2, "pin"))
        tracker.forget(first)
        assertFalse(tracker.isReady(first, 2, "pin"))
    }
}
