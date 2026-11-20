package gg.grounds.dialogue.minestom

import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.presentation.DialoguePresenter
import java.time.Duration
import kotlin.math.sin
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.metadata.display.TextDisplayMeta
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@EnvTest
internal class NpcOptionsTest {
    @Test
    fun optionsAreBoundedAndCanDisableTheFold() {
        assertEquals(
            NpcPanelOptions(12.0, 12.0),
            NpcPanelOptions.from(PresentationPreferences.defaults()),
        )
        assertEquals(0.0, NpcPanelOptions.from(preferences("npcChoiceAngle" to "0")).choiceAngle)
        for (value in listOf("-1", "65", "12.25", "NaN")) {
            assertThrows<DialoguePresenter.FallbackRequired> {
                NpcPanelOptions.from(preferences("npcChoiceGap" to value))
            }
        }
        assertEquals(12.5, NpcPanelOptions.from(preferences("npcChoiceGap" to "12.5")).choiceGap)
        for (value in listOf("NaN", "Infinity", "-1", "36", "bad")) {
            assertThrows<DialoguePresenter.FallbackRequired> {
                NpcPanelOptions.from(preferences("npcChoiceAngle" to value))
            }
        }
    }

    @Test
    fun nativeOptionsChangeLiveWithoutRestartAndScrollbarIsPrivateAndCleanedUp(env: Env) {
        NativeReviewRegressionTest.F(env, 7).use { fixture ->
            val ticket = fixture.start("npc", false)
            val handle =
                (NativeReviewRegressionTest.done(env, ticket.result())
                        as DialogueStartResult.Started)
                    .handle
            val visit = handle.snapshot().nodeVisitId()
            val plain = PlainTextComponentSerializer.plainText()
            for (angle in listOf("0", "30", "12")) {
                NativeReviewRegressionTest.done(
                    env,
                    handle.updatePreferences(
                        preferences("npcChoiceAngle" to angle, "npcChoiceGap" to "24")
                    ),
                )
                val displays =
                    fixture.player.instance.entities.filter {
                        it.entityType == EntityType.TEXT_DISPLAY
                    }
                assertTrue(displays.size <= 32)
                val option =
                    displays
                        .map { it.entityMeta as TextDisplayMeta }
                        .first { plain.serialize(it.text).contains("Choice 0") }
                assertEquals(
                    -sin(Math.toRadians(angle.toDouble()) / 2),
                    option.leftRotation[1].toDouble(),
                    1e-6,
                )
                if (angle == "0") {
                    val factor = option.scale.x() / 0.32
                    val left = option.translation.x() / 0.008 + 160 + (1 - 240.0 / 2) * factor
                    assertTrue(left - 320 >= 24 - 1e-5)
                }
                val bars = displays.filter {
                    plain
                        .serialize((it.entityMeta as TextDisplayMeta).text)
                        .codePoints()
                        .anyMatch { code -> code in 0xe700 until 0xe780 }
                }
                assertEquals(2, bars.size, "Both indicator faces remain visible on the same track")
                assertTrue(bars.all { it.viewers == setOf(fixture.player) })
                assertEquals(visit, handle.snapshot().nodeVisitId())
                assertEquals("npc", ticket.status().effectiveSurface)
            }
            NativeReviewRegressionTest.done(env, ticket.cancel(DialogueEndReason.CANCELLED))
            assertTrue(
                fixture.player.instance.entities.none { it.entityType == EntityType.TEXT_DISPLAY }
            )
        }
    }

    private fun preferences(vararg effects: Pair<String, String>) =
        PresentationPreferences("npc", Duration.ZERO, false, mapOf(*effects))
}
