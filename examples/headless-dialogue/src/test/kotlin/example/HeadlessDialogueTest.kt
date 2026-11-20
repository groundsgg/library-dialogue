package example

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HeadlessDialogueTest {
    @Test
    fun realScenarioCompletesWithoutGrounds() {
        assertEquals("HEADLESS_DIALOGUE_OK", HeadlessDialogue.runSmoke())
    }

    @Test
    fun sharedPresenterKeepsTheSessionAcrossProfiles() {
        assertEquals("PRESENTATION_OK", HeadlessDialogue.runPresentationSmoke())
    }
}
