package example;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HeadlessDialogueTest {
  @Test
  void realScenarioCompletesWithoutGrounds() {
    assertEquals("HEADLESS_DIALOGUE_OK", HeadlessDialogue.runSmoke());
  }

  @Test
  void sharedPresenterKeepsTheSessionAcrossProfiles() {
    assertEquals("PRESENTATION_OK", HeadlessDialogue.runPresentationSmoke());
  }
}
