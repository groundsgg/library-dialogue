package gg.grounds.dialogue.minestom.render;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.presentation.*;
import java.util.*;
import net.kyori.adventure.text.*;
import org.junit.jupiter.api.Test;

class MinecraftWidgetEncoderTest {
  static String plain(Component c) {
    var s = new StringBuilder();
    if (c instanceof TextComponent t) s.append(t.content());
    for (var child : c.children()) s.append(plain(child));
    return s.toString();
  }

  static java.util.stream.Stream<Component> parts(Component component) {
    return java.util.stream.Stream.concat(
        java.util.stream.Stream.of(component),
        component.children().stream().flatMap(MinecraftWidgetEncoderTest::parts));
  }

  @Test
  void scrollbarIsFrontOnlyAndCannotMirrorAcrossPanelBacks() {
    var base = RenderFixtures.frame("npc", "Hello", 5);
    var scroll =
        new WidgetFrame.Scrollbar(
            new WidgetFrame.Rect(600, 0, 2, 132), new WidgetFrame.Rect(600, 0, 2, 56));
    var f =
        new WidgetFrame(
            base.semantic(), base.widgets(), base.fontPin(), base.theme(), null, scroll);
    var bar =
        new MinecraftWidgetEncoder()
            .encode(f, RenderFixtures.ASSETS).widgets().stream()
                .filter(w -> w.kind() == WidgetFrame.Kind.SCROLLBAR)
                .findFirst()
                .orElseThrow();
    assertTrue(plain(bar.front()).codePoints().anyMatch(c -> c >= 0xe700 && c < 0xe780));
    assertFalse(
        plain(bar.back()).codePoints().anyMatch(c -> c >= 0xe700 && c <= 0xe781),
        "The rotated back must not paint a mirrored bar through the closed panel backs");
  }

  @Test
  void panelsUseBakedPreviewTexturesWithoutOverlappingStripGlyphs() {
    for (String key : List.of("hud", "npc")) {
      var frame = RenderFixtures.frame(key, "Hello", 5);
      var encoded = new MinecraftWidgetEncoder().encode(frame, RenderFixtures.ASSETS);
      for (var widget : encoded.widgets()) {
        if (widget.kind() == WidgetFrame.Kind.HINT) continue;
        for (var face : List.of(widget.front(), widget.back())) {
          var codes = plain(face).codePoints().toArray();
          assertFalse(
              java.util.Arrays.stream(codes).anyMatch(c -> c >= 0xe001 && c <= 0xe100),
              "Overlapping coloured column glyphs cause the photographed striped panels");
          long tiles =
              java.util.Arrays.stream(codes).filter(c -> c >= 0xe400 && c < 0xe500).count();
          assertTrue(
              tiles >= 3 && tiles <= 68, "A panel must use the bounded precomposed preview tiles");
        }
      }
    }
  }

  @Test
  void lettersExplicitlyReceiveDepthSeparatingShadow() {
    var frame = RenderFixtures.frame("npc", "Hello", 5);
    var encoded = new MinecraftWidgetEncoder().encode(frame, RenderFixtures.ASSETS);
    var text =
        encoded.widgets().stream().filter(w -> w.id().equals("text")).findFirst().orElseThrow();
    var letters =
        parts(text.front())
            .filter(c -> c instanceof TextComponent t && t.content().equals("Hello"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        net.kyori.adventure.text.format.ShadowColor.shadowColor(0xff000000), letters.shadowColor());
    assertTrue(
        parts(text.back())
            .filter(c -> c instanceof TextComponent t && !t.content().isEmpty())
            .allMatch(
                c -> net.kyori.adventure.text.format.ShadowColor.none().equals(c.shadowColor())));
  }

  @Test
  void sameWidgetsEncodeForHudAndNpc() {
    var encoder = new MinecraftWidgetEncoder();
    for (String key : List.of("hud", "npc")) {
      var frame = RenderFixtures.frame(key, "Hello", 5);
      var encoded = encoder.encode(frame, RenderFixtures.ASSETS);
      assertEquals(
          frame.widgets().stream().map(WidgetFrame.Widget::id).toList(),
          encoded.widgets().stream().map(EncodedFrame.EncodedWidget::id).toList());
      assertEquals(
          "ask",
          encoded.widgets().stream()
              .filter(w -> w.id().equals("choice:ask"))
              .findFirst()
              .orElseThrow()
              .choiceId());
    }
  }

  @Test
  void revealChangesOnlyGlyphPrefix() {
    var encoder = new MinecraftWidgetEncoder();
    var a = encoder.encode(RenderFixtures.frame("hud", "Hello", 1), RenderFixtures.ASSETS);
    var b = encoder.encode(RenderFixtures.frame("hud", "Hello", 5), RenderFixtures.ASSETS);
    var wa = a.widgets().getFirst();
    var wb = b.widgets().getFirst();
    assertEquals(wa.bounds(), wb.bounds());
    assertFalse(plain(wa.front()).contains("Hello"));
    assertTrue(plain(wb.front()).contains("Hello"));
    assertFalse(plain(wb.back()).contains("Hello"));
  }

  @Test
  void namesStayCentered() {
    var frame = RenderFixtures.frame("npc", "Hello", 5);
    var name =
        frame.widgets().stream().filter(w -> w.id().equals("speaker")).findFirst().orElseThrow();
    assertEquals(
        name.bounds().x() + name.bounds().width() / 2,
        name.textBounds().x() + name.textBounds().width() / 2,
        0.001);
    var encoded = new MinecraftWidgetEncoder().encode(frame, RenderFixtures.ASSETS);
    assertEquals(
        name.bounds(),
        encoded.widgets().stream()
            .filter(w -> w.id().equals("speaker"))
            .findFirst()
            .orElseThrow()
            .bounds());
  }

  @Test
  void paletteAndShadowAreApplied() {
    var frame = RenderFixtures.frame("hud", "Hello", 5);
    var altered =
        new WidgetFrame(
            frame.semantic(),
            frame.widgets(),
            frame.fontPin(),
            new DialogueTheme(
                "other",
                Map.of(
                    "text", 0x123456, "panel", 0x010203, "selected", 0xabcdef, "muted", 0x555555),
                1.08,
                java.time.Duration.ofMillis(150)));
    assertNotEquals(
        new MinecraftWidgetEncoder().encode(frame, RenderFixtures.ASSETS).hud(),
        new MinecraftWidgetEncoder().encode(altered, RenderFixtures.ASSETS).hud());
  }

  @Test
  void unsupportedRasterDiagnoses() {
    var frame = RenderFixtures.frame("hud", "Hello", 5);
    assertThrows(
        DialoguePresenter.FallbackRequired.class,
        () ->
            new MinecraftWidgetEncoder()
                .encode(
                    new WidgetFrame(frame.semantic(), frame.widgets(), "wrong-pin", frame.theme()),
                    RenderFixtures.ASSETS));
    var old = frame.widgets().getFirst();
    var bad =
        new WidgetFrame.Widget(
            old.id(),
            old.kind(),
            new WidgetFrame.Rect(0, 0, 320, 300),
            old.textBounds(),
            old.text(),
            old.layout(),
            old.visibleGraphemes(),
            false,
            true,
            1,
            old.transition(),
            null);
    assertThrows(
        DialoguePresenter.FallbackRequired.class,
        () ->
            new MinecraftWidgetEncoder()
                .encode(
                    new WidgetFrame(frame.semantic(), List.of(bad), frame.fontPin(), frame.theme()),
                    RenderFixtures.ASSETS));
  }
}
