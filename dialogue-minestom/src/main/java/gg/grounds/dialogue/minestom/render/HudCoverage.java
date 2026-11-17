package gg.grounds.dialogue.minestom.render;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.format.*;

/** Spatial fade over the ordinary bottom HUD area; no game-state or world-time mutation. */
public final class HudCoverage {
  private HudCoverage() {}

  public static int fadeGuiPixels() {
    return 128;
  }

  public static Component compose(Component hud, double canvasWidth, boolean visible) {
    if (!visible) return hud;
    int width = 256 * 128;
    double start = (canvasWidth - width) / 2;
    return shift(start)
        .append(
            Component.text(("\ue200\uf800").repeat(128))
                .font(Key.key("dialogue:mask"))
                .color(TextColor.color(0xffffff))
                .shadowColor(ShadowColor.none()))
        .append(shift(-start - width))
        .append(hud);
  }

  private static Component shift(double pixels) {
    int amount = (int) Math.abs(pixels);
    if (Math.abs(pixels) > 32767 || pixels * 2 != Math.rint(pixels * 2))
      throw new IllegalArgumentException("UNSUPPORTED_RASTER");
    var s = new StringBuilder();
    for (int i = 0; i < 16; i++)
      if ((amount & (1 << i)) != 0) s.appendCodePoint((pixels >= 0 ? 0xf810 : 0xf830) + i);
    if (Math.abs(pixels) - amount != 0) s.appendCodePoint(pixels >= 0 ? 0xf801 : 0xf802);
    return Component.text(s.toString())
        .font(Key.key("dialogue:spacing"))
        .shadowColor(ShadowColor.none());
  }
}
