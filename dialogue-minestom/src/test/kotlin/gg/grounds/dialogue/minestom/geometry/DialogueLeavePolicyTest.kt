package gg.grounds.dialogue.minestom.geometry

import gg.grounds.dialogue.core.definition.DialogueDefinition.Interaction
import gg.grounds.dialogue.core.definition.DialogueDefinition.LookAway
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import java.time.Duration
import net.minestom.server.coordinate.Vec
import net.minestom.server.instance.block.Block
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@EnvTest
class DialogueLeavePolicyTest {
    private fun policy(enabled: Boolean) =
        DialogueLeavePolicy(
            Interaction(LookAway(enabled, 110.0, 600, 95.0), 6.0),
            Duration.ofMillis(600),
        )

    @Test
    fun ordinaryLookingDoesNotCancel() {
        val policy = policy(true)
        assertTrue(policy.observe(80.0, 3.0, false, 0).isEmpty)
        assertTrue(policy.observe(111.0, 3.0, false, 1).isEmpty)
        assertTrue(policy.observe(100.0, 3.0, false, 300_000_001).isEmpty)
        assertTrue(policy.observe(94.0, 3.0, false, 400_000_001).isEmpty)
        assertTrue(policy.observe(111.0, 3.0, false, 500_000_001).isEmpty)
        assertTrue(policy.observe(111.0, 3.0, false, 1_099_000_001).isEmpty)
        assertEquals(
            DialogueEndReason.LOOKED_AWAY,
            policy.observe(111.0, 3.0, false, 1_100_000_001).orElseThrow(),
        )
    }

    @Test
    fun firstSampleAtZeroCounts() {
        val policy = policy(true)
        assertTrue(policy.observe(111.0, 3.0, false, 0).isEmpty)
        assertTrue(policy.observe(111.0, 3.0, false, 599_000_000).isEmpty)
        assertEquals(
            DialogueEndReason.LOOKED_AWAY,
            policy.observe(100.0, 3.0, false, 600_000_000).orElseThrow(),
        )
    }

    @Test
    fun answersProtectAngleButNotDistance() {
        val policy = policy(true)
        assertTrue(policy.observe(150.0, 3.0, true, 0).isEmpty)
        assertTrue(policy.observe(150.0, 6.1, true, 100_000_000).isEmpty)
        assertTrue(policy.observe(150.0, 6.1, true, 699_000_000).isEmpty)
        assertEquals(
            DialogueEndReason.OUT_OF_RANGE,
            policy.observe(150.0, 6.1, true, 700_000_000).orElseThrow(),
        )
    }

    @Test
    fun disabledAngleStillEnforcesDistanceAndInvalidNumbers() {
        val policy = policy(false)
        assertTrue(policy.observe(180.0, 3.0, false, 0).isEmpty)
        assertTrue(policy.observe(180.0, 3.0, false, 9_000_000_000).isEmpty)
        assertTrue(policy.observe(0.0, 7.0, false, 10_000_000_000).isEmpty)
        assertEquals(
            DialogueEndReason.OUT_OF_RANGE,
            policy.observe(0.0, 7.0, false, 10_600_000_000).orElseThrow(),
        )
        assertEquals(
            DialogueEndReason.HOST_CHANGED,
            policy.observe(Double.NaN, 3.0, false, 0).orElseThrow(),
        )
        assertEquals(
            DialogueEndReason.HOST_CHANGED,
            policy.observe(0.0, Double.POSITIVE_INFINITY, false, 0).orElseThrow(),
        )
    }

    @Test
    fun nativeOcclusionRespectsBlockShapes(env: Env) {
        val world = env.createFlatInstance()
        world.loadChunk(0, 0).join()
        val first = Vec(.5, 43.5, .5)
        val second = Vec(.5, 43.5, 3.5)
        assertTrue(MinestomOcclusion.visible(world, first, second))
        world.setBlock(0, 43, 2, Block.STONE)
        assertFalse(MinestomOcclusion.visible(world, first, second))
        world.setBlock(0, 43, 2, Block.STONE_SLAB)
        assertTrue(MinestomOcclusion.visible(world, Vec(.5, 43.8, .5), Vec(.5, 43.8, 3.5)))
    }
}
