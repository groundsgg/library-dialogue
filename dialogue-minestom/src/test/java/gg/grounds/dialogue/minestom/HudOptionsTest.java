package gg.grounds.dialogue.minestom;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.minestom.asset.AssetBundle;
import java.io.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.*;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class HudOptionsTest {
  @Test
  void originalHudUsesOnePanelEmbeddedNameAndArrowRows(Env env) throws Exception {
    try (var f = new NativeReviewRegressionTest.F(env, 7)) {
      var ticket = f.start("hud", false);
      NativeReviewRegressionTest.done(env, ticket.result());
      var component = f.hudFrames.getLast();
      var text =
          net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
              .serialize(component);
      assertEquals(
          2,
          text.codePoints().filter(c -> c == 0xe600 || c == 0xe601).count(),
          "Original HUD has one two-tile shared backdrop");
      assertFalse(
          text.codePoints().anyMatch(c -> c >= 0xe400 && c < 0xe500),
          "Individual NPC card frames do not belong in the prototype HUD");
      assertTrue(text.contains("> Choice 0"));
    }
  }

  @Test
  void nativeHudUsesCompactColumnsAndCentersTheWholeLeftCanvas(Env env) throws Exception {
    try (var f = new NativeReviewRegressionTest.F(env, 7)) {
      var t = f.start("hud", false);
      var h =
          ((DialogueStartResult.Started) NativeReviewRegressionTest.done(env, t.result())).handle();
      long visit = h.snapshot().nodeVisitId();
      var inspector = new NativeWidths(f.options.assets());
      assertEquals(240, inspector.width(f.hudFrames.getLast()), .001);
      for (String position : List.of("left", "right", "below")) {
        NativeReviewRegressionTest.done(
            env,
            h.updatePreferences(
                new PresentationPreferences(
                    "hud", Duration.ZERO, false, Map.of("hudChoices", position))));
        var out = f.hudFrames.getLast();
        assertEquals(position.equals("below") ? 240 : 300, inspector.width(out), .001);
        assertEquals(
            0,
            inspector.leftmostInk(out),
            .001,
            "Left answers must participate in native actionbar centering");
        assertEquals(visit, h.snapshot().nodeVisitId());
        assertEquals("hud", t.status().effectiveSurface());
      }
    }
  }

  @Test
  void backgroundStyleAndAnswerPlacementAreIndependentLivePreferences(Env env) throws Exception {
    for (boolean wrapped : List.of(false, true)) {
      try (var f = new NativeReviewRegressionTest.F(env, 7, wrapped)) {
        var ticket = f.start("hud", false);
        var h =
            ((DialogueStartResult.Started) NativeReviewRegressionTest.done(env, ticket.result()))
                .handle();
        long visit = h.snapshot().nodeVisitId();
        String selected = h.snapshot().selectedChoiceId();
        var inspector = new NativeWidths(f.options.assets());
        for (String style : List.of("shared", "split", "cards")) {
          for (String position : List.of("below", "left", "right")) {
            NativeReviewRegressionTest.done(
                env,
                h.updatePreferences(
                    new PresentationPreferences(
                        "hud",
                        Duration.ZERO,
                        false,
                        Map.of("hudChoices", position, "hudStyle", style))));
            var frame = f.hudFrames.getLast();
            String json =
                net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                    .serialize(frame);
            String prefix = style.equals("shared") ? "hud_" : "hud_" + style + "_";
            assertTrue(
                json.contains("dialogue:" + prefix + position + (wrapped ? "_20_3" : "_10_3")),
                style + "/" + position + "/wrapped=" + wrapped + " " + json);
            double expectedWidth =
                style.equals("cards")
                    ? position.equals("below") ? 249 : position.equals("left") ? 312 : 309
                    : position.equals("below") ? 240 : 300;
            assertEquals(expectedWidth, inspector.width(frame), .001);
            if (style.equals("cards")) {
              double left = position.equals("right") ? 174 : 9;
              assertFalse(inspector.texts.isEmpty());
              for (var text : inspector.texts)
                if (text.text().contains("Choice") || text.text().contains("Details"))
                  assertEquals(left, text.x(), .001, text.text());
            }
            assertEquals(0, inspector.leftmostInk(frame), .001);
            assertEquals(visit, h.snapshot().nodeVisitId());
            assertEquals(selected, h.snapshot().selectedChoiceId());
            assertEquals("hud", ticket.status().effectiveSurface());
          }
        }
        var snapshot = h.snapshot();
        NativeReviewRegressionTest.done(
            env,
            h.submit(
                new DialogueInput.Confirm(
                    new DialogueInput.InputStamp(
                        snapshot.sessionId(), snapshot.renderRevision(), 100),
                    selected)));
        for (String position : List.of("below", "left", "right")) {
          NativeReviewRegressionTest.done(
              env,
              h.updatePreferences(
                  new PresentationPreferences(
                      "hud",
                      Duration.ZERO,
                      false,
                      Map.of("hudChoices", position, "hudStyle", "cards"))));
          String json =
              net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                  .serialize(f.hudFrames.getLast());
          assertTrue(json.contains("dialogue:hud_cards_" + position + "_10_0"), json);
          assertEquals(
              position.equals("below") ? 249 : position.equals("left") ? 312 : 309,
              inspector.width(f.hudFrames.getLast()),
              .001);
          assertEquals("hud", ticket.status().effectiveSurface());
        }
      }
    }
  }

  @Test
  void scrollbarIsOnlyEmittedForOverflowAndMovesWithThePublishedPage(Env env) throws Exception {
    for (int answers : List.of(3, 7))
      try (var f = new NativeReviewRegressionTest.F(env, answers)) {
        var ticket = f.start("hud", false);
        var h =
            ((DialogueStartResult.Started) NativeReviewRegressionTest.done(env, ticket.result()))
                .handle();
        var plain =
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        var first = plain.serialize(f.hudFrames.getLast());
        assertEquals(answers > 3, first.codePoints().anyMatch(c -> c >= 0xe700 && c < 0xe780));
        if (answers > 3) {
          String before =
              net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                  .serialize(f.hudFrames.getLast());
          for (int slot = 1; slot <= 6; slot++) {
            f.packet(
                new net.minestom.server.network.packet.client.play.ClientHeldItemChangePacket(
                    (short) slot));
            NativeReviewRegressionTest.ticks(env);
          }
          assertEquals("c6", h.snapshot().selectedChoiceId());
          var out = f.hudFrames.getLast();
          assertNotEquals(
              before,
              net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                  .serialize(out));
          assertEquals(
              2, plain.serialize(out).codePoints().filter(c -> c >= 0xe700 && c < 0xe780).count());
          assertEquals(240, new NativeWidths(f.options.assets()).width(out), .001);
        }
      }
  }

  // Compute advances from actual PNG ink and bitmap definitions, not the presenter/encoder's width
  // helper.
  static final class NativeWidths {
    final Map<String, byte[]> files;
    final Map<String, JsonArray> fonts = new HashMap<>();
    final Map<String, java.awt.image.BufferedImage> images = new HashMap<>();
    final Map<String, double[]> glyphs = new HashMap<>();

    record NativeText(String text, double x, double width) {}

    final List<NativeText> texts = new ArrayList<>();

    NativeWidths(AssetBundle bundle) {
      files = bundle.entries();
    }

    double[] glyph(String font, int cp) throws Exception {
      String key = font + ":" + cp;
      if (glyphs.containsKey(key)) return glyphs.get(key);
      var providers =
          fonts.computeIfAbsent(
              font,
              k ->
                  JsonParser.parseString(
                          new String(
                              files.get("assets/dialogue/font/" + k + ".json"),
                              java.nio.charset.StandardCharsets.UTF_8))
                      .getAsJsonObject()
                      .getAsJsonArray("providers"));
      for (var raw : providers) {
        var p = raw.getAsJsonObject();
        if (p.get("type").getAsString().equals("space")) {
          var n = p.getAsJsonObject("advances").get(Character.toString(cp));
          if (n != null) {
            var value = new double[] {n.getAsDouble(), 0};
            glyphs.put(key, value);
            return value;
          }
          continue;
        }
        var rows = p.getAsJsonArray("chars");
        for (int row = 0; row < rows.size(); row++) {
          int[] chars = rows.get(row).getAsString().codePoints().toArray();
          for (int col = 0; col < chars.length; col++)
            if (chars[col] == cp) {
              String path = "assets/dialogue/textures/" + p.get("file").getAsString().split(":")[1];
              var image = images.get(path);
              if (image == null) {
                image = ImageIO.read(new ByteArrayInputStream(files.get(path)));
                images.put(path, image);
              }
              int cw = image.getWidth() / chars.length,
                  ch = image.getHeight() / rows.size(),
                  right = -1;
              for (int x = 0; x < cw; x++)
                for (int y = 0; y < ch; y++)
                  if ((image.getRGB(col * cw + x, row * ch + y) >>> 24) != 0)
                    right = Math.max(right, x);
              double scale = p.get("height").getAsDouble() / ch;
              var value =
                  new double[] {Math.floor((right + 1) * scale + .5) + 1, (right + 1) * scale};
              glyphs.put(key, value);
              return value;
            }
        }
      }
      throw new AssertionError("Unresolved native glyph " + cp);
    }

    double walk(Component c, double[] state) throws Exception {
      if (c instanceof TextComponent t && !t.content().isEmpty()) {
        String font = t.font().value();
        double start = state[0];
        for (int cp : t.content().codePoints().toArray()) {
          var g = glyph(font, cp);
          if (g[1] > 0) state[1] = Math.min(state[1], state[0]);
          state[0] += g[0];
        }
        if (t.content().codePoints().anyMatch(Character::isLetter))
          texts.add(new NativeText(t.content(), start, state[0] - start));
      }
      for (var child : c.children()) walk(child, state);
      return state[0];
    }

    double width(Component c) throws Exception {
      texts.clear();
      return walk(c, new double[] {0, Double.POSITIVE_INFINITY});
    }

    double leftmostInk(Component c) throws Exception {
      var s = new double[] {0, Double.POSITIVE_INFINITY};
      walk(c, s);
      return s[1];
    }
  }
}
