import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Maintenance tool only. Normal builds consume the checked-in metrics, never host fonts. */
class GenerateDefaultMetrics {
  public static void main(String[] args) throws Exception {
    byte[] source = Files.readAllBytes(Path.of(args[0]));
    Font font =
        Font.createFont(Font.TRUETYPE_FONT, new java.io.ByteArrayInputStream(source))
            .deriveFont(12f);
    var context = new FontRenderContext(new AffineTransform(), true, true);
    var out =
        new StringBuilder(
                "# Generated with Temurin 25.0.2, font size 12; do not regenerate during"
                    + " sourcebuild.\n"
                    + "fontSha256=")
            .append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)))
            .append("\nlineHeight=16\n");
    var points = new TreeSet<Integer>();
    for (int c = 32; c <= 126; c++) points.add(c);
    for (int c = 160; c <= 383; c++) points.add(c);
    points.addAll(
        List.of(0x2013, 0x2014, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x20ac, 0x2191, 0x2193));
    for (int c : points)
      if (font.canDisplay(c))
        out.append("glyph.")
            .append(c)
            .append('=')
            .append(
                String.format(
                    Locale.ROOT,
                    "%.6f",
                    font.getStringBounds(new String(Character.toChars(c)), context).getWidth()))
            .append('\n');
    Files.writeString(Path.of(args[1]), out, StandardCharsets.UTF_8);
  }
}
