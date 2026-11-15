import gg.grounds.dialogue.core.validation.ContentFingerprint;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Maintenance-only generator. Normal library builds use the checked-in bytes. */
class GenerateMinecraftAssets {
  static byte[] json(Object value) {
    return ContentFingerprint.canonicalBytes(value);
  }

  static Map<String, Object> bitmap(String file, int height, int ascent, List<String> chars) {
    return Map.of(
        "type", "bitmap", "file", file, "height", height, "ascent", ascent, "chars", chars);
  }

  static void put(Map<String, byte[]> files, String path, Object data) {
    files.put(path, json(data));
  }

  static byte[] png(BufferedImage image) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  static BufferedImage previewPanel(int width, int height, boolean selected) {
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    var graphics = image.createGraphics();
    pixelPanel(graphics, 0, 0, width, height, selected);
    graphics.dispose();
    return image;
  }

  private static void pixelPanel(Graphics2D g, int x, int y, int w, int h, boolean selected) {
    g.setColor(selected ? new Color(25, 40, 55, 240) : new Color(9, 15, 24, 240));
    g.fillRect(x + 2, y, w - 4, h);
    g.fillRect(x, y + 2, w, h - 4);
    g.setColor(selected ? new Color(255, 205, 117) : new Color(78, 104, 127));
    g.drawPolyline(
        new int[] {x + 2, x + w - 3, x + w - 1, x + w - 1, x + w - 3, x + 2, x, x, x + 2},
        new int[] {y, y, y + 2, y + h - 3, y + h - 1, y + h - 1, y + h - 3, y + 2, y},
        9);
    g.setColor(selected ? new Color(130, 104, 62) : new Color(34, 52, 69));
    g.drawRect(x + 3, y + 3, w - 7, h - 7);
  }

  static void hudAssets(Map<String, byte[]> files, Path source, Map<String, Object> baseSpaces)
      throws Exception {
    var original = ImageIO.read(source.resolve("letters.png").toFile());
    var panel = ImageIO.read(source.resolve("panel.png").toFile());
    var rows = Files.readAllLines(source.resolve("glyphs.txt"));
    var letters =
        new BufferedImage(original.getWidth(), rows.size() * 256, BufferedImage.TYPE_INT_ARGB);
    var chars = new ArrayList<String>();
    var properties = new StringBuilder("lineHeight=10\nglyph.32=3\nglyph.160=3\n");
    for (int row = 0; row < rows.size(); row++) {
      var grid = rows.get(row).split(" ");
      var text = new StringBuilder();
      for (int col = 0; col < grid.length; col++) {
        int cp = Integer.parseInt(grid[col], 16);
        text.appendCodePoint(cp == 160 ? 0 : cp);
        int right = -1;
        for (int y = 0; y < 128; y++)
          for (int x = 0; x < 12; x++) {
            int pixel = original.getRGB(col * 12 + x, row * 128 + y);
            letters.setRGB(col * 12 + x, row * 256 + y, pixel);
            if ((pixel >>> 24) != 0) right = Math.max(right, x);
          }
        if (cp != 0 && cp != 160)
          properties
              .append("glyph.")
              .append(cp)
              .append('=')
              .append(Math.round((right + 1) * .75f) + 1)
              .append('\n');
      }
      chars.add(text.toString());
    }
    files.put("assets/dialogue/textures/font/hud_letters.png", png(letters));
    files.put(
        "assets/dialogue/font-source/prototype_hud_panel.png",
        Files.readAllBytes(source.resolve("panel.png")));
    files.put(
        "assets/dialogue/hud_metrics.properties",
        properties.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var spaces = new TreeMap<String, Object>(baseSpaces);
    spaces.put(" ", 3);
    spaces.put("\u00a0", 3);
    var spacing = Map.of("type", "space", "advances", spaces);
    var ascents = new TreeSet<Integer>(List.of(0, -10, -17, -27, -37, 11, 1, -3, -13, -23));
    for (String placement : List.of("below", "left", "right"))
      for (int rowHeight : List.of(10, 20)) {
        boolean below = placement.equals("below");
        int logicalHeight = below ? 84 + 3 * (rowHeight - 10) : rowHeight == 10 ? 51 : 75;
        int baseline = logicalHeight + 10;
        ascents.add(baseline - 7);
        for (int line = 0; line < 3; line++) ascents.add(baseline - 17 - line * 10);
        for (int slot = 0; slot < 3; slot++)
          for (int line = 0; line < rowHeight / 10; line++)
            ascents.add(baseline - (below ? 50 : 7) - slot * rowHeight - line * 10);
        String key = "hud_" + placement + "_" + rowHeight + "_3";
        int width = below ? 320 : 400;
        var image = new BufferedImage(width, 256, BufferedImage.TYPE_INT_ARGB);
        if (below)
          for (int y = 0; y < 128 + (rowHeight == 20 ? 40 : 0); y++)
            for (int x = 0; x < 320; x++) {
              int from = rowHeight == 10 || y < 103 ? y : y < 143 ? 100 : y - 40;
              image.setRGB(x, y, panel.getRGB(x, from));
            }
        else {
          int height = rowHeight == 10 ? 68 : 100;
          var g = image.createGraphics();
          g.setColor(new Color(9, 15, 24, 220));
          g.fillRoundRect(0, 0, width, height, 10, 10);
          g.setColor(new Color(78, 104, 127, 230));
          g.drawRoundRect(0, 0, width - 1, height - 1, 10, 10);
          int speech = placement.equals("left") ? 184 : 0,
              answers = placement.equals("left") ? 0 : 220;
          g.setColor(new Color(25, 40, 55, 220));
          g.fillRoundRect(speech + 6, 7, 204, height - 16, 6, 6);
          g.setColor(new Color(15, 27, 39, 230));
          g.fillRoundRect(answers + 6, 7, 168, height - 16, 6, 6);
          g.dispose();
        }
        files.put("assets/dialogue/textures/font/" + key + ".png", png(image));
        put(
            files,
            "assets/dialogue/font/" + key + ".json",
            Map.of(
                "providers",
                List.of(
                    bitmap("dialogue:font/" + key + ".png", 192, baseline, List.of("\ue600\ue601")),
                    spacing)));
      }
    for (String style : List.of("split", "cards"))
      for (String placement : List.of("below", "left", "right"))
        for (int rowHeight : List.of(10, 20))
          for (int count : style.equals("cards") ? List.of(0, 1, 2, 3) : List.of(3)) {
            boolean below = placement.equals("below"), cards = style.equals("cards");
            int answerHeight =
                cards ? count * (rowHeight + 10) + Math.max(0, count - 1) * 3 : 3 * rowHeight + 14;
            int logicalHeight =
                cards && count == 0 ? 48 : below ? 54 + answerHeight : Math.max(48, answerHeight);
            int baseline = logicalHeight + 10, step = cards ? rowHeight + 13 : rowHeight;
            ascents.add(baseline - 7);
            for (int line = 0; line < 3; line++) ascents.add(baseline - 17 - line * 10);
            for (int slot = 0; slot < count; slot++)
              for (int line = 0; line < rowHeight / 10; line++)
                ascents.add(baseline - (below ? 61 : 7) - slot * step - line * 10);
            if (cards) {
              ascents.add(-5);
              ascents.add(-15);
              for (int slot = 0; slot < count; slot++)
                for (int line = 0; line < rowHeight / 10; line++)
                  ascents.add(baseline - (below ? 59 : 5) - slot * step - line * 10);
            }
            String key = "hud_" + style + "_" + placement + "_" + rowHeight + "_" + count;
            int width =
                below
                    ? 320
                    : cards && count == 0 ? 216 : cards && placement.equals("left") ? 416 : 400;
            var image = new BufferedImage(width, 256, BufferedImage.TYPE_INT_ARGB);
            var g = image.createGraphics();
            g.scale(4d / 3, 4d / 3);
            double speechX =
                placement.equals("left") && (!cards || count > 0) ? (cards ? 150 : 138) : 0;
            double answerX = below ? 0 : placement.equals("left") ? 0 : 165;
            double answerY = below ? 54 : 0;
            hudRegion(g, speechX, 0, below ? 240 : 162, 48, false);
            if (cards) {
              for (int slot = 0; slot < count; slot++)
                hudRegion(
                    g, answerX, answerY + slot * step, below ? 240 : 135, rowHeight + 10, true);
            } else hudRegion(g, answerX, answerY, below ? 240 : 135, answerHeight, true);
            g.dispose();
            files.put("assets/dialogue/textures/font/" + key + ".png", png(image));
            put(
                files,
                "assets/dialogue/font/" + key + ".json",
                Map.of(
                    "providers",
                    List.of(
                        bitmap(
                            "dialogue:font/" + key + ".png",
                            192,
                            baseline,
                            List.of("\ue600\ue601")),
                        spacing)));
          }
    for (int ascent : ascents)
      put(
          files,
          "assets/dialogue/font/hud_a_" + ascent + ".json",
          Map.of(
              "providers",
              List.of(bitmap("dialogue:font/hud_letters.png", 192, ascent, chars), spacing)));
    var bars = new BufferedImage(256, 192, BufferedImage.TYPE_INT_ARGB);
    var barChars = new StringBuilder();
    for (int height = 1; height <= 128; height++) {
      barChars.appendCodePoint(0xe700 + height - 1);
      for (int y = 0; y < height; y++)
        for (int x = 0; x < 2; x++) bars.setRGB((height - 1) * 2 + x, y, 0xffffffff);
    }
    files.put("assets/dialogue/textures/font/hud_scrollbar.png", png(bars));
    for (int ascent = 0; ascent <= 192; ascent++)
      put(
          files,
          "assets/dialogue/font/hud_scroll_a_" + ascent + ".json",
          Map.of(
              "providers",
              List.of(
                  bitmap(
                      "dialogue:font/hud_scrollbar.png",
                      192,
                      ascent,
                      List.of(barChars.toString())))));
    System.out.println("Frozen prototype HUD fonts=" + ascents.size());
  }

  static void hudRegion(Graphics2D g, double x, double y, double w, double h, boolean answer) {
    g.setColor(new Color(9, 15, 24, 220));
    g.fill(new java.awt.geom.RoundRectangle2D.Double(x, y, w, h, 7.5, 7.5));
    g.setColor(new Color(78, 104, 127, 230));
    g.draw(new java.awt.geom.RoundRectangle2D.Double(x, y, w - .75, h - .75, 7.5, 7.5));
    g.setColor(answer ? new Color(15, 27, 39, 230) : new Color(25, 40, 55, 220));
    g.fill(new java.awt.geom.RoundRectangle2D.Double(x + 4.5, y + 3.75, w - 9, h - 7.5, 4.5, 4.5));
  }

  public static void main(String[] args) throws Exception {
    Path source = Path.of(args[0]), output = Path.of(args[1]);
    var points = new TreeSet<Integer>();
    for (int c = 33; c <= 255; c++) if (c != 160) points.add(c);
    points.addAll(
        List.of(0x2013, 0x2014, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x20ac, 0x2191, 0x2193));
    var font = Font.createFont(Font.TRUETYPE_FONT, source.toFile()).deriveFont(12f);
    points.removeIf(c -> !font.canDisplay(c));
    var codes = new ArrayList<>(points);
    int rows = (codes.size() + 15) / 16;
    var atlas = new BufferedImage(256, rows * 256, BufferedImage.TYPE_INT_ARGB);
    var graphics = atlas.createGraphics();
    graphics.setFont(font);
    graphics.setColor(Color.WHITE);
    graphics.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    var chars = new ArrayList<String>();
    var glyphs = new TreeMap<String, Object>();
    var metrics =
        new StringBuilder(
            "# Frozen native bitmap advances; source generator is maintenance-only.\n"
                + "lineHeight=16\n"
                + "glyph.32=4\n"
                + "glyph.160=4\n");
    for (int row = 0; row < rows; row++) {
      var line = new StringBuilder();
      for (int col = 0; col < 16; col++) {
        int index = row * 16 + col;
        if (index >= codes.size()) {
          line.append('\0');
          continue;
        }
        int cp = codes.get(index);
        line.appendCodePoint(cp);
        graphics.drawString(Character.toString(cp), col * 16, row * 256 + 12);
        int right = -1;
        for (int x = 0; x < 16; x++)
          for (int y = 0; y < 256; y++)
            if ((atlas.getRGB(col * 16 + x, row * 256 + y) >>> 24) != 0) right = Math.max(right, x);
        int advance = right + 2;
        glyphs.put(Integer.toString(cp), Map.of("column", col, "row", row, "advance", advance));
        metrics.append("glyph.").append(cp).append('=').append(advance).append('\n');
      }
      chars.add(line.toString());
    }
    graphics.dispose();
    var spaces = new TreeMap<String, Object>();
    spaces.put(" ", 4);
    spaces.put("\u00a0", 4);
    spaces.put("\uf800", -1);
    spaces.put("\uf801", 0.5);
    spaces.put("\uf802", -0.5);
    for (int i = 0; i < 16; i++) {
      spaces.put(Character.toString(0xf810 + i), 1 << i);
      spaces.put(Character.toString(0xf830 + i), -(1 << i));
    }
    var mask = new BufferedImage(256, 200, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < 200; y++) {
      double t = Math.min(1, y / 128d), e = t * t * (3 - 2 * t);
      int alpha = (int) Math.round(255 * e * e);
      for (int x = 0; x < 256; x++) {
        int a = alpha;
        if (alpha > 0 && alpha < 32) a = ((x * 157 + y * 67) & 255) < alpha * 8 ? 32 : 0;
        mask.setRGB(x, y, a << 24);
      }
    }
    for (String profile : List.of("public-26.2", "grounds-26.3")) {
      var files = new TreeMap<String, byte[]>();
      files.put("assets/dialogue/textures/font/letters.png", png(atlas));

      files.put("assets/dialogue/textures/font/hud-mask.png", png(mask));
      files.put("assets/dialogue/font-source/dejavu_sans.ttf", Files.readAllBytes(source));
      files.put(
          "assets/dialogue/font-source/font-license.txt",
          Files.readAllBytes(source.resolveSibling("FONT-LICENSE.txt")));
      files.put(
          "assets/dialogue/metrics.properties",
          metrics.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var focusImage = new BufferedImage(192, 256, BufferedImage.TYPE_INT_ARGB);
      for (int y = 0; y < 256; y++)
        for (int x = 0; x < 192; x++) {
          double edge =
              Math.min(Math.min(x / 40d, (191 - x) / 40d), Math.min(y / 40d, (255 - y) / 40d));
          double t = Math.clamp(edge, 0, 1);
          int alpha = (int) Math.round(64 * t * t * (3 - 2 * t));
          if (alpha > 0 && alpha < 32) alpha = ((x * 157 + y * 67) & 255) < alpha * 8 ? 32 : 0;
          focusImage.setRGB(x, y, alpha << 24);
        }
      files.put("assets/dialogue/textures/font/npc-focus.png", png(focusImage));
      put(
          files,
          "assets/dialogue/font/focus.json",
          Map.of(
              "providers",
              List.of(bitmap("dialogue:font/npc-focus.png", 256, 128, List.of("\ue300")))));
      // Original blue/gold preview art, precomposed before it reaches the native font renderer.
      // Every horizontal tile contains its own final border+fill pixels: no coplanar paint layers.
      var panelChars = new ArrayList<List<String>>();
      for (int style = 0; style < 3; style++) {
        var image = new BufferedImage(8, 48 * 256, BufferedImage.TYPE_INT_ARGB);
        var rowsOfChars = new ArrayList<String>();
        for (int hi = 0; hi < 8; hi++) {
          int h = new int[] {28, 40, 56, 72, 24, 32, 48, 64}[hi];
          var original = previewPanel(320, h, style == 1);
          if (style == 2)
            for (int y = 0; y < h; y++)
              for (int x = 0; x < 320; x++) {
                int pixel = original.getRGB(x, y), alpha = pixel >>> 24;
                if (alpha != 0 && alpha != 255)
                  original.setRGB(x, y, (x + y) % 12 == 0 ? 0xff101b28 : 0xff0b1420);
              }
          for (int part = 0; part < 6; part++) {
            int index = hi * 6 + part,
                w = part < 2 ? 4 : 1 << (part - 2),
                from = part == 0 ? 0 : part == 1 ? 316 : 6;
            int dx = 0, dy = index * 256;
            for (int y = 0; y < h; y++)
              for (int x = 0; x < w; x++)
                image.setRGB(dx + x, dy + y, original.getRGB(from + x, y));
          }
        }
        for (int row = 0; row < 48; row++)
          rowsOfChars.add(Character.toString(0xe400 + style * 48 + row));
        panelChars.add(rowsOfChars);
        files.put(
            "assets/dialogue/textures/font/panels-"
                + new String[] {"normal", "selected", "back"}[style]
                + ".png",
            png(image));
      }
      var spacing = Map.of("type", "space", "advances", spaces);
      put(files, "assets/dialogue/font/spacing.json", Map.of("providers", List.of(spacing)));
      put(
          files,
          "assets/dialogue/font/mask.json",
          Map.of(
              "providers",
              List.of(bitmap("dialogue:font/hud-mask.png", 200, 106, List.of("\ue200")), spacing)));
      for (int a = -64; a <= 256; a += 2) {
        var providers = new ArrayList<Object>();
        providers.add(bitmap("dialogue:font/letters.png", 256, a, chars));
        for (int style = 0; style < 3; style++)
          providers.add(
              bitmap(
                  "dialogue:font/panels-"
                      + new String[] {"normal", "selected", "back"}[style]
                      + ".png",
                  256,
                  a,
                  panelChars.get(style)));
        providers.add(spacing);
        put(files, "assets/dialogue/font/a_" + a + ".json", Map.of("providers", providers));
      }
      if (profile.equals("grounds-26.3")) {
        files.put(
            "assets/dialogue/post_effect/vignette.json",
            ("""
            {"targets":{"swap":{}},"passes":[{"vertex_shader":"minecraft:core/screenquad","fragment_shader":"dialogue:post/vignette","inputs":[{"sampler_name":"In","target":"minecraft:main"}],"output":"swap"},{"vertex_shader":"minecraft:core/screenquad","fragment_shader":"minecraft:post/blit","inputs":[{"sampler_name":"In","target":"swap"}],"uniforms":{"BlitConfig":[{"name":"ColorModulate","type":"vec4","value":[1,1,1,1]}]},"output":"minecraft:main"}]}
            """)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put(
            "assets/dialogue/shaders/post/vignette.fsh",
            ("""
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            uniform sampler2D InSampler;
            layout(location = 0) in vec2 texCoord;
            layout(location = 0) out vec4 fragColor;
            void main() {
                vec4 scene = texture(InSampler, texCoord);
                float edge = smoothstep(0.35, 1.35, length((texCoord - vec2(0.5)) * 2.0));
                fragColor = vec4(scene.rgb * (1.0 - 0.15 * edge), scene.a);
            }
            """)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      hudAssets(files, args.length > 2 ? Path.of(args[2]) : Path.of("tools/prototype-hud"), spaces);
      var hashes = new TreeMap<String, String>();
      files.forEach((k, v) -> hashes.put(k, ContentFingerprint.hash(v).sha256()));
      String pin =
          ContentFingerprint.hash(
                  json(Map.of("profile", profile, "files", hashes, "glyphs", glyphs)))
              .sha256();
      var manifest =
          Map.of(
              "schemaVersion",
              1,
              "profile",
              profile,
              "fontSourceSha256",
              ContentFingerprint.hash(Files.readAllBytes(source)).sha256(),
              "files",
              hashes,
              "glyphs",
              glyphs,
              "pin",
              pin,
              "minAscent",
              -64,
              "maxAscent",
              256,
              "maxRectangleHeight",
              256);
      Path root = output.resolve(profile);
      for (var entry : files.entrySet()) {
        Path target = root.resolve(entry.getKey());
        Files.createDirectories(target.getParent());
        Files.write(target, entry.getValue());
      }
      Files.write(root.resolve("manifest.json"), json(manifest));
      System.out.println(profile + " fontPin=" + pin + " glyphs=" + glyphs.size());
    }
  }
}
