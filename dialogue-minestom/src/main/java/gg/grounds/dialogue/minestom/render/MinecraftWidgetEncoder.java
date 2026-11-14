package gg.grounds.dialogue.minestom.render;

import gg.grounds.dialogue.minestom.asset.AssetBundle;
import gg.grounds.dialogue.presentation.*;
import gg.grounds.dialogue.presentation.WidgetFrame.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/** Native projection only. All wrapping, reveal and hit rectangles come from the presenter. */
public final class MinecraftWidgetEncoder {
  public EncodedFrame encode(WidgetFrame frame, AssetBundle bundle) {
    if (!frame.fontPin().equals(bundle.pin())) throw failure("FONT_PIN_MISMATCH");
    if (frame.widgets().size() > 32) throw failure("WIDGET_LIMIT");
    double
        bottom =
            frame.widgets().stream()
                .mapToDouble(w -> w.bounds().y() + w.bounds().height())
                .max()
                .orElse(0),
        width =
            frame.widgets().stream()
                .mapToDouble(w -> w.bounds().x() + w.bounds().width())
                .max()
                .orElse(0);
    var hud = new Canvas(bundle);
    var widgets = new ArrayList<EncodedFrame.EncodedWidget>();
    for (var w : frame.widgets()) {
      validate(w.bounds());
      var front = new Canvas(bundle);
      var back = new Canvas(bundle);
      draw(w, frame.theme(), front, 0, 0, 0, true);
      draw(w, frame.theme(), back, 0, 0, 0, false);
      front.move(w.bounds().width());
      back.move(w.bounds().width());
      draw(w, frame.theme(), hud, w.bounds().x(), w.bounds().y(), bottom, true);
      widgets.add(
          new EncodedFrame.EncodedWidget(
              w.id(),
              w.kind(),
              front.component,
              back.component,
              w.bounds(),
              w.choiceId(),
              w.enabled(),
              w.visualScale(),
              w.transition()));
    }
    hud.move(width);
    long bytes = wireBytes(hud.component);
    for (var w : widgets) {
      bytes += wireBytes(w.front()) + wireBytes(w.back());
      if (bytes > 65536) throw failure("FRAME_LIMIT");
    }
    return new EncodedFrame(widgets, hud.component);
  }

  private static long wireBytes(Component component) {
    return GsonComponentSerializer.gson()
        .serialize(component)
        .getBytes(StandardCharsets.UTF_8)
        .length;
  }

  private static void validate(Rect r) {
    if (r.width() > 512
        || r.height() > 256
        || r.width() != Math.rint(r.width())
        || r.height() != Math.rint(r.height())) throw failure("UNSUPPORTED_RASTER");
  }

  private static DialoguePresenter.FallbackRequired failure(String code) {
    return new DialoguePresenter.FallbackRequired(code);
  }

  private static int colour(DialogueTheme theme, String key, int fallback) {
    return theme.colours().getOrDefault(key, fallback);
  }

  private static void draw(
      Widget w,
      DialogueTheme theme,
      Canvas canvas,
      double ox,
      double oy,
      double baseline,
      boolean letters) {
    boolean panel = w.kind() != Kind.HINT;
    double width = w.bounds().width(), height = w.bounds().height();
    if (panel) {
      int border =
          colour(theme, w.selected() || w.kind() == Kind.SPEAKER ? "selected" : "muted", 0x9ba9b7);
      canvas.roundRect(ox, oy, (int) width, (int) height, baseline, border);
      if (width >= 8 && height >= 8)
        canvas.roundRect(
            ox + 2,
            oy + 2,
            (int) width - 4,
            (int) height - 4,
            baseline,
            colour(theme, "panel", 0x152335));
    }
    if (!letters) return;
    var lines = w.layout().visible(w.visibleGraphemes());
    double x = ox + w.textBounds().x() - w.bounds().x(),
        y = oy + w.textBounds().y() - w.bounds().y();
    for (int n = 0; n < lines.size(); n++) {
      String text = lines.get(n);
      double advance =
          w.layout().lines().get(n).advances().stream()
              .limit(gg.grounds.dialogue.core.text.Graphemes.split(text).size())
              .mapToDouble(Double::doubleValue)
              .sum();
      canvas.draw(
          x,
          text,
          canvas.font(baseline - y - n * canvas.assets.metrics().lineHeight()),
          advance,
          colour(theme, w.enabled() ? "text" : "muted", 0xf3eee4),
          true);
    }
  }

  private static final class Canvas {
    final AssetBundle assets;
    Component component = Component.empty().decoration(TextDecoration.ITALIC, false);
    double cursor;

    Canvas(AssetBundle assets) {
      this.assets = assets;
    }

    Key font(double ascent) {
      if (ascent != Math.rint(ascent)
          || !assets.hasEntry("assets/dialogue/font/a_" + (int) ascent + ".json"))
        throw failure("UNSUPPORTED_RASTER");
      return Key.key("dialogue:a_" + (int) ascent);
    }

    void move(double target) {
      double distance = target - cursor;
      if (distance == 0) return;
      double doubled = distance * 2;
      if (doubled != Math.rint(doubled) || Math.abs(distance) > 32767)
        throw failure("UNSUPPORTED_RASTER");
      int whole = (int) Math.abs(distance);
      var glyphs = new StringBuilder();
      for (int i = 0; i < 16; i++)
        if ((whole & (1 << i)) != 0) glyphs.appendCodePoint((distance > 0 ? 0xf810 : 0xf830) + i);
      if (Math.abs(distance) - whole != 0) glyphs.appendCodePoint(distance > 0 ? 0xf801 : 0xf802);
      component =
          component.append(
              Component.text(glyphs.toString())
                  .font(Key.key("dialogue:spacing"))
                  .shadowColor(ShadowColor.none()));
      cursor = target;
    }

    void draw(double x, String text, Key font, double advance, int colour, boolean shadow) {
      move(x);
      var part = Component.text(text).font(font).color(TextColor.color(colour));
      part = part.shadowColor(shadow ? ShadowColor.shadowColor(0xff000000) : ShadowColor.none());
      component = component.append(part);
      cursor = x + advance;
    }

    void columns(double x, double y, int width, int height, double baseline, int colour) {
      if (width <= 0 || height <= 0) return;
      if (height > 256) throw failure("UNSUPPORTED_RASTER");
      String column = Character.toString(0xe000 + height) + Character.toString(0xf800);
      draw(x, column.repeat(width), font(baseline - y), width, colour, false);
    }

    void roundRect(double x, double y, int width, int height, double baseline, int colour) {
      if (width < 4 || height < 4) {
        columns(x, y, width, height, baseline, colour);
        return;
      }
      columns(x + 2, y, width - 4, height, baseline, colour);
      columns(x, y + 2, 2, height - 4, baseline, colour);
      columns(x + width - 2, y + 2, 2, height - 4, baseline, colour);
    }
  }
}
