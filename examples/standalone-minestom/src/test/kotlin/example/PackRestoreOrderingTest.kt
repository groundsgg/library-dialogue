package example

import gg.grounds.dialogue.minestom.asset.PackStateTracker
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import net.kyori.adventure.resource.ResourcePackStatus
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.player.PlayerResourcePackStatusEvent
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@EnvTest
class PackRestoreOrderingTest {
    @Test
    fun applicationRefreshSeesReadinessBeforeChildHostListenerRuns(env: Env) {
        val world = env.createFlatInstance()
        val player = env.createConnection().connect(world, Pos(0.0, 40.0, 0.0))
        val tracker = PackStateTracker()
        val id = UUID.randomUUID()
        tracker.expect(player, 1, id, "pin")
        val event =
            PlayerResourcePackStatusEvent(player, id, ResourcePackStatus.SUCCESSFULLY_LOADED)
        val calls = AtomicInteger()
        DialogueDemo.refreshAfterPackStatus(tracker, event) {
            assertTrue(tracker.isReady(player, 1, "pin"))
            calls.incrementAndGet()
        }
        assertEquals(1, calls.get())
        tracker.observe(player, id, event.status)
        assertTrue(tracker.isReady(player, 1, "pin"))
    }
}
