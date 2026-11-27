package gg.grounds.dialogue.core.runtime

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class PresentationPreferencesTest {
    @Test
    fun defaultConstructionPreservesExistingFactoryValues() {
        val preferences = PresentationPreferences()
        assertEquals("hud", preferences.surfaceKey)
        assertEquals(Duration.ofMillis(35), preferences.graphemeDelay)
        assertFalse(preferences.reducedMotion)
        assertEquals(emptyMap<String, String>(), preferences.effects)
        assertEquals(PresentationPreferences.defaults(), preferences)
        assertEquals(
            PresentationPreferences("hud", Duration.ofMillis(35), false, emptyMap()),
            preferences,
        )
        assertEquals(PresentationPreferences.defaults().hashCode(), preferences.hashCode())
        assertEquals(
            "PresentationPreferences[surfaceKey=hud, graphemeDelay=PT0.035S, reducedMotion=false, effects={}]",
            preferences.toString(),
        )
    }

    @Test
    fun namedArgumentsOverrideOnlySelectedDefaults() {
        val preferences = PresentationPreferences(reducedMotion = true, surfaceKey = "npc")
        assertEquals(
            PresentationPreferences("npc", Duration.ofMillis(35), true, emptyMap()),
            preferences,
        )
        assertNotEquals(PresentationPreferences(), preferences)
    }

    @Test
    fun effectsRemainAnImmutableSnapshot() {
        val effects = mutableMapOf("typingSound" to "true")
        val preferences = PresentationPreferences(effects = effects)
        effects["typingSound"] = "false"
        effects["hideHud"] = "true"
        assertEquals(mapOf("typingSound" to "true"), preferences.effects)
        assertThrows(UnsupportedOperationException::class.java) {
            (preferences.effects as MutableMap<String, String>)["typingSound"] = "false"
        }
        assertEquals(mapOf("typingSound" to "true"), preferences.effects)
    }

    @Test
    fun revealDelayRetainsInclusiveZeroToSixtySecondRange() {
        for (delay in listOf(Duration.ZERO, Duration.ofSeconds(60))) {
            assertEquals(delay, PresentationPreferences(graphemeDelay = delay).graphemeDelay)
        }
        for (delay in listOf(Duration.ofNanos(-1), Duration.ofSeconds(60).plusNanos(1))) {
            assertThrows(IllegalArgumentException::class.java) {
                PresentationPreferences(graphemeDelay = delay)
            }
        }
    }
}
