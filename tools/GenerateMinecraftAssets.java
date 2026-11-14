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
    var stripes = new BufferedImage(16, 4096, BufferedImage.TYPE_INT_ARGB);
    var stripChars = new ArrayList<String>();
    for (int row = 0; row < 16; row++) {
      var line = new StringBuilder();
      for (int col = 0; col < 16; col++) {
        int h = row * 16 + col + 1;
        line.appendCodePoint(0xe000 + h);
        for (int y = 0; y < h; y++) stripes.setRGB(col, row * 256 + y, 0xffffffff);
      }
      stripChars.add(line.toString());
    }
    var spaces = new TreeMap<String, Object>();
    spaces.put(" ", 4);
    spaces.put("\u00a0", 4);
    spaces.put("\uf800", -1);
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
      files.put("assets/dialogue/textures/font/stripes.png", png(stripes));
      files.put("assets/dialogue/textures/font/hud-mask.png", png(mask));
      files.put("assets/dialogue/font-source/dejavu_sans.ttf", Files.readAllBytes(source));
      files.put(
          "assets/dialogue/font-source/font-license.txt",
          Files.readAllBytes(source.resolveSibling("FONT-LICENSE.txt")));
      files.put(
          "assets/dialogue/metrics.properties",
          metrics.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var spacing = Map.of("type", "space", "advances", spaces);
      put(files, "assets/dialogue/font/spacing.json", Map.of("providers", List.of(spacing)));
      put(
          files,
          "assets/dialogue/font/mask.json",
          Map.of(
              "providers",
              List.of(bitmap("dialogue:font/hud-mask.png", 200, 106, List.of("\ue200")), spacing)));
      for (int a = -32; a <= 256; a += 2)
        put(
            files,
            "assets/dialogue/font/a_" + a + ".json",
            Map.of(
                "providers",
                List.of(
                    bitmap("dialogue:font/letters.png", 256, a, chars),
                    bitmap("dialogue:font/stripes.png", 256, a, stripChars),
                    spacing)));
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
              -512,
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
