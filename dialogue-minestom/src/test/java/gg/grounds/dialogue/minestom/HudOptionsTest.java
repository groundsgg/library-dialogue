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

  // Compute advances from actual PNG ink and bitmap definitions, not the presenter/encoder's width
  // helper.
  static final class NativeWidths {
    final Map<String, byte[]> files;
    final Map<String, JsonArray> fonts = new HashMap<>();
    final Map<String, java.awt.image.BufferedImage> images = new HashMap<>();
    final Map<String, double[]> glyphs = new HashMap<>();

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
        for (int cp : t.content().codePoints().toArray()) {
          var g = glyph(font, cp);
          if (g[1] > 0) state[1] = Math.min(state[1], state[0]);
          state[0] += g[0];
        }
      }
      for (var child : c.children()) walk(child, state);
      return state[0];
    }

    double width(Component c) throws Exception {
      return walk(c, new double[] {0, Double.POSITIVE_INFINITY});
    }

    double leftmostInk(Component c) throws Exception {
      var s = new double[] {0, Double.POSITIVE_INFINITY};
      walk(c, s);
      return s[1];
    }
  }
}
