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
    boolean prototype = frame.chrome() != null;
    if (!frame.fontPin().equals(prototype ? bundle.hudMetrics().pin() : bundle.pin()))
      throw failure("FONT_PIN_MISMATCH");
    if (frame.widgets().size() > 32) throw failure("WIDGET_LIMIT");
    var bounds = frame.canvasBounds();
    double bottom = prototype ? frame.chrome().baseline() : bounds.y() + bounds.height(),
        width = bounds.width();
    var hud = new Canvas(bundle, prototype);
    if (prototype) hud.chrome(frame.chrome(), bounds.x());
    var widgets = new ArrayList<EncodedFrame.EncodedWidget>();
    for (var w : frame.widgets()) {
      validate(w.bounds());
      var front = new Canvas(bundle, prototype);
      var back = new Canvas(bundle, prototype);
      draw(w, frame.theme(), front, 0, 0, 0, true, !prototype);
      draw(w, frame.theme(), back, 0, 0, 0, false, !prototype);
      front.move(w.bounds().width());
      back.move(w.bounds().width());
      draw(
          w,
          frame.theme(),
          hud,
          w.bounds().x() - bounds.x(),
          w.bounds().y(),
          bottom,
          true,
          !prototype);
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
      boolean letters,
      boolean panels) {
    boolean panel = panels && w.kind() != Kind.HINT;
    double width = w.bounds().width(), height = w.bounds().height();
    if (panel) {
      canvas.panel(
          ox,
          oy,
          (int) width,
          (int) height,
          baseline,
          !letters ? 2 : w.selected() || w.kind() == Kind.SPEAKER ? 1 : 0);
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
          canvas.font(baseline - y - n * canvas.metrics.lineHeight()),
          advance,
          colour(
              theme,
              !panels
                  ? (w.kind() == Kind.SPEAKER || w.kind() == Kind.CHOICE && w.selected()
                      ? "selected"
                      : w.kind() == Kind.CHOICE ? "muted" : w.kind() == Kind.HINT ? "hint" : "text")
                  : w.enabled() ? "text" : "muted",
              0xf3eee4),
          true);
    }
  }

  private static final class Canvas {
    final AssetBundle assets;
    final boolean prototype;
    final gg.grounds.dialogue.presentation.FontMetrics metrics;
    Component component = Component.empty().decoration(TextDecoration.ITALIC, false);
    double cursor;

    Canvas(AssetBundle assets, boolean prototype) {
      this.assets = assets;
      this.prototype = prototype;
      metrics = prototype ? assets.hudMetrics() : assets.metrics();
    }

    void chrome(WidgetFrame.Chrome chrome, double left) {
      String key = chrome.key();
      if (!assets.hasEntry("assets/dialogue/font/" + key + ".json")) throw failure("MISSING_GLYPH");
      double width = chrome.bounds().width() / 2, x = chrome.bounds().x() - left;
      draw(x, "\ue600", Key.key("dialogue:" + key), width + 1, 0xffffff, false);
      draw(x + width, "\ue601", Key.key("dialogue:" + key), width + 1, 0xffffff, false);
    }

    Key font(double ascent) {
      String key = (prototype ? "hud_a_" : "a_") + (int) ascent;
      if (ascent != Math.rint(ascent) || !assets.hasEntry("assets/dialogue/font/" + key + ".json"))
        throw failure("UNSUPPORTED_RASTER");
      return Key.key("dialogue:" + key);
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

    void panel(double x, double y, int width, int height, double baseline, int style) {
      int hi =
          switch (height) {
            case 28 -> 0;
            case 40 -> 1;
            case 56 -> 2;
            case 72 -> 3;
            case 24 -> 4;
            case 32 -> 5;
            case 48 -> 6;
            case 64 -> 7;
            default -> throw failure("UNSUPPORTED_RASTER");
          };
      if (width < 8) throw failure("UNSUPPORTED_RASTER");
      int base = 0xe400 + style * 48 + hi * 6;
      tile(x, y, baseline, base, 4);
      int remaining = width - 8;
      double pen = x + 4;
      for (int bit = 3; bit >= 0; bit--) {
        int size = 1 << bit;
        int count = remaining / size;
        if (count > 0) {
          String glyphs =
              (Character.toString(base + 2 + bit) + Character.toString(0xf800)).repeat(count);
          draw(pen, glyphs, font(baseline - y), size * count, 0xffffff, false);
          pen += size * count;
          remaining -= size * count;
        }
      }
      tile(x + width - 4, y, baseline, base + 1, 4);
    }

    void tile(double x, double y, double baseline, int code, int width) {
      // BitmapProvider's full-ink tile advance is width+1; the next explicit move removes it.
      draw(x, Character.toString(code), font(baseline - y), width + 1, 0xffffff, false);
    }
  }
}
