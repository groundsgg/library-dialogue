import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.TreeSet

/** Maintenance tool only. Normal builds consume the checked-in metrics, never host fonts. */
object GenerateDefaultMetrics {
    @JvmStatic
    fun main(args: Array<String>) {
        val source = Files.readAllBytes(Path.of(args[0]))
        val font = Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(source)).deriveFont(12f)
        val context = FontRenderContext(AffineTransform(), true, true)
        val output =
            StringBuilder(
                    "# Generated with Temurin 25.0.2, font size 12; do not regenerate during" +
                        " sourcebuild.\n" +
                        "fontSha256="
                )
                .append(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source))
                )
                .append("\nlineHeight=16\n")
        val points = TreeSet<Int>()
        for (codePoint in 32..126) points.add(codePoint)
        for (codePoint in 160..383) points.add(codePoint)
        points.addAll(
            listOf(0x2013, 0x2014, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x20ac, 0x2191, 0x2193)
        )
        for (codePoint in points) {
            if (font.canDisplay(codePoint)) {
                output
                    .append("glyph.")
                    .append(codePoint)
                    .append('=')
                    .append(
                        String.format(
                            Locale.ROOT,
                            "%.6f",
                            font
                                .getStringBounds(String(Character.toChars(codePoint)), context)
                                .width,
                        )
                    )
                    .append('\n')
            }
        }
        Files.writeString(Path.of(args[1]), output, StandardCharsets.UTF_8)
    }
}
