package gg.grounds.dialogue.presentation;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.text.ResolvedText;
import java.util.*;
import org.junit.jupiter.api.Test;

class TextLayoutTest {
  static final FontMetrics METRICS =
      new FontMetrics() {
        public double width(String g) {
          return 4;
        }

        public double lineHeight() {
          return 10;
        }

        public String pin() {
          return "test-4x10";
        }
      };

  @Test
  void fullTextReservedDuringReveal() {
    var result =
        new TextLayout()
            .layout(new ResolvedText("ab cd", "en"), METRICS, new TextLayout.TextLimits(12, 3));
    assertTrue(result.isValid());
    assertEquals(12, result.width());
    assertEquals(20, result.height());
    assertEquals(List.of("", ""), result.visible(0));
    assertEquals(List.of("ab", ""), result.visible(2));
    assertEquals(List.of("ab ", "cd"), result.visible(5));
    assertEquals(20, result.height());
  }

  @Test
  void unicodeAndOverflowAreExplicit() {
    var l = new TextLayout();
    assertTrue(
        l.layout(new ResolvedText("你好世界", "zh"), METRICS, new TextLayout.TextLimits(8, 3))
            .isValid());
    assertEquals(
        "LAYOUT_OVERFLOW",
        l.layout(new ResolvedText("abcdefghij", "en"), METRICS, new TextLayout.TextLimits(8, 2))
            .problem());
    assertEquals(
        "MISSING_GLYPH",
        l.layout(
                new ResolvedText("你好", "zh"),
                FontMetrics.defaults(),
                new TextLayout.TextLimits(320, 3))
            .problem());
  }

  @Test
  void newlinesKeepOriginalGraphemeOffsets() {
    var r =
        new TextLayout()
            .layout(new ResolvedText("a\nb", "en"), METRICS, new TextLayout.TextLimits(12, 3));
    assertEquals(List.of("a", ""), r.visible(2));
    assertEquals(List.of("a", "b"), r.visible(3));
  }

  @Test
  void fontAndMetricsShareOneAssetPin() {
    assertEquals(
        "3d89c53e253dbbb31cb77e2267cbddc54c0e7445d4c2a917207d8a8d9536ac20",
        FontMetrics.defaults().pin());
  }
}
