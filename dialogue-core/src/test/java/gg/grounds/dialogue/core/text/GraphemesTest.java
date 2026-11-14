package gg.grounds.dialogue.core.text;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GraphemesTest {
  @Test
  void clustersAreNeverSplit() {
    assertEquals(5, Graphemes.split("e\u0301 👨‍👩‍👧‍👦 🇩🇪").size());
    assertEquals("e\u0301", Graphemes.reveal("e\u0301x", 1));
  }
}
