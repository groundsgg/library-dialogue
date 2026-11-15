package gg.grounds.dialogue.minestom.asset;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class MinecraftAssetsTest {
  @Test
  void previewTileImagesHaveBoundedDecodedMemory() throws Exception {
    var bundle = AssetBundle.loadDefault(MinecraftPackProfile.public262());
    long decoded = 0;
    for (String style : List.of("normal", "selected", "back")) {
      var image =
          ImageIO.read(
              new ByteArrayInputStream(
                  bundle.entries().get("assets/dialogue/textures/font/panels-" + style + ".png")));
      decoded += (long) image.getWidth() * image.getHeight() * 4;
    }
    assertTrue(
        decoded <= 2 * 1024 * 1024,
        "Each ascent loads bitmap images; do not multiply large transparent atlases across native"
            + " fonts");
  }

  @Test
  void bakedPanelsPreservePreviewFrameAndFillPixels() throws Exception {
    var bundle = AssetBundle.loadDefault(MinecraftPackProfile.grounds263());
    var normal =
        ImageIO.read(
            new ByteArrayInputStream(
                bundle.entries().get("assets/dialogue/textures/font/panels-normal.png")));
    var selected =
        ImageIO.read(
            new ByteArrayInputStream(
                bundle.entries().get("assets/dialogue/textures/font/panels-selected.png")));
    // First cell is the original 4px left edge of the 28px preview name/answer frame.
    assertEquals(0, normal.getRGB(0, 0));
    assertEquals(0xff4e687f, normal.getRGB(2, 0));
    assertEquals(0xff223445, normal.getRGB(3, 3));
    assertEquals(0xff82683e, selected.getRGB(3, 3));
    assertEquals(0xffffcd75, selected.getRGB(2, 0));
    // The preview painter overlaps two alpha-240 fills: 240 + 240*(15/255) = 254.
    assertEquals(0xfe080f18, normal.getRGB(2, 10));
  }

  @Test
  void nativeManifestDescribesActualRasterAndOptionalEffects() {
    var publicBundle = AssetBundle.loadDefault(MinecraftPackProfile.public262());
    var metadata =
        com.google.gson.JsonParser.parseString(
                new String(publicBundle.manifestBytes(), java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    assertEquals(-64, metadata.get("minAscent").getAsInt());
    var grounds = AssetBundle.loadDefault(MinecraftPackProfile.grounds263());
    assertTrue(grounds.hasEntry("assets/dialogue/post_effect/vignette.json"));
    assertTrue(grounds.hasEntry("assets/dialogue/shaders/post/vignette.fsh"));
  }

  @Test
  void bitmapAdvancesMatchFrozenAtlas() throws Exception {
    var bundle = AssetBundle.loadDefault(MinecraftPackProfile.grounds263());
    var image =
        ImageIO.read(
            new ByteArrayInputStream(
                bundle.entries().get("assets/dialogue/textures/font/letters.png")));
    var spec = bundle.glyphs().get((int) 'A');
    int right = -1;
    for (int x = 0; x < 16; x++)
      for (int y = 0; y < 256; y++)
        if ((image.getRGB(spec.column() * 16 + x, spec.row() * 256 + y) >>> 24) != 0)
          right = Math.max(right, x);
    assertEquals(right + 2, bundle.metrics().width("A"));
    assertEquals(4, bundle.metrics().width(" "));
    assertTrue(bundle.metrics().width("ä") > 0);
    assertThrows(IllegalArgumentException.class, () -> bundle.metrics().width("\ud83d\ude00"));
  }

  @Test
  void deterministicBundlePinAndProfiles() {
    var a = AssetBundle.loadDefault(MinecraftPackProfile.grounds263());
    var b = AssetBundle.loadDefault(MinecraftPackProfile.grounds263());
    assertEquals(a.pin(), b.pin());
    assertEquals(a.pin(), a.metrics().pin());
    assertEquals(777, a.profile().protocol());
    assertEquals(97, a.profile().packMajor());
    assertEquals(1, a.profile().packMinor());
    var old = AssetBundle.loadDefault(MinecraftPackProfile.public262());
    assertEquals(776, old.profile().protocol());
    assertFalse(old.profile().postEffects());
    assertTrue(a.entries().keySet().stream().noneMatch(k -> k.startsWith("assets/minecraft/")));
  }

  @Test
  void wrongPinAndTamperedAtlasAreRejected() {
    var a = AssetBundle.loadDefault(MinecraftPackProfile.grounds263());
    var map = new TreeMap<>(a.entries());
    var copy = map.get("assets/dialogue/textures/font/letters.png").clone();
    copy[copy.length - 1] ^= 1;
    map.put("assets/dialogue/textures/font/letters.png", copy);
    assertThrows(
        IllegalArgumentException.class,
        () -> AssetBundle.load(a.profile(), a.manifestBytes(), map));
  }

  @Test
  void entriesAndManifestAreDefensivelyCopied() {
    var a = AssetBundle.loadDefault(MinecraftPackProfile.public262());
    var first = a.entries();
    first.values().iterator().next()[0] ^= 1;
    assertEquals(a.pin(), AssetBundle.loadDefault(MinecraftPackProfile.public262()).pin());
    assertThrows(UnsupportedOperationException.class, () -> first.put("x", new byte[0]));
  }

  @Test
  void frozenGlyphAdvanceFixtures() {
    var a = AssetBundle.loadDefault(MinecraftPackProfile.public262());
    assertEquals(9, a.metrics().width("A"));
    assertEquals(4, a.metrics().width(" "));
    assertTrue(a.entries().containsKey("assets/dialogue/font/spacing.json"));
  }
}
