package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.Choice
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.validation.HostCatalog
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class DialogueLocaleTest {
    @Test
    fun localeRefreshKeepsVisitSelectionAndCues() {
        val definition =
            DialogueBuilder.dialogue("demo:locale")
                .locales("en", "en", "de")
                .node(
                    "start",
                    Message(
                        "npc",
                        "hello",
                        "demo:wave",
                        listOf(
                            Choice("again", "c", "start", true, null),
                            Choice("leave", "bye", "end", false, null),
                        ),
                        null,
                    ),
                )
                .node("end", End("completed"))
                .build()
        val rig =
            TestRig(
                definition,
                HostCatalog(
                    "v1",
                    setOf("npc"),
                    emptyMap(),
                    emptyMap(),
                    setOf("demo:wave"),
                    emptySet(),
                    false,
                ),
            )
        val handle = rig.start()
        val old = handle.snapshot()
        val select =
            handle.submit(
                DialogueInput.Select(
                    DialogueInput.InputStamp(old.sessionId(), old.renderRevision(), 1),
                    "leave",
                )
            )
        rig.scheduler.drain()
        assertEquals(InputResult.ACCEPTED, TestRig.done(select))
        val refresh = handle.refreshLocale(LocaleContext("de", 1, "en"))
        rig.scheduler.drain()
        assertEquals(RefreshResult.UPDATED, TestRig.done(refresh))
        val after = handle.snapshot()
        assertEquals(old.nodeVisitId(), after.nodeVisitId())
        assertEquals("leave", after.selectedChoiceId())
        assertEquals("Hallo Freund", after.text())
        assertEquals("Mechaniker", after.speaker())
        assertEquals(after.totalGraphemes(), after.revealedGraphemes())
        assertTrue(after.renderRevision() > old.renderRevision())
        assertEquals(1, rig.cues.size)
        val stale =
            handle.submit(
                DialogueInput.Confirm(
                    DialogueInput.InputStamp(old.sessionId(), old.renderRevision(), 2),
                    "leave",
                )
            )
        rig.scheduler.drain()
        assertEquals(InputResult.STALE, TestRig.done(stale))
        val older = handle.refreshLocale(LocaleContext("en", 0, "en"))
        rig.scheduler.drain()
        assertEquals(RefreshResult.STALE, TestRig.done(older))
        assertEquals("de", handle.snapshot().localeTag())
    }

    @Test
    fun requestedRegionalLocaleReportsEffectiveFallback() {
        val rig = TestRig()
        val handle = rig.start()
        val future = handle.refreshLocale(LocaleContext("en-US", 1, "en"))
        rig.scheduler.drain()
        assertEquals(RefreshResult.UPDATED, TestRig.done(future))
        assertEquals("en", handle.snapshot().localeTag())
        assertEquals("Hello friend", handle.snapshot().text())
    }
}
