package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.text.Graphemes
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueTheme
import gg.grounds.dialogue.presentation.FontMetrics
import gg.grounds.dialogue.presentation.WidgetFrame
import gg.grounds.dialogue.presentation.WidgetFrame.Kind
import gg.grounds.dialogue.presentation.WidgetFrame.NavigationDirection
import gg.grounds.dialogue.presentation.WidgetFrame.Rect
import gg.grounds.dialogue.presentation.WidgetFrame.Widget
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer

/** Native projection only. All wrapping, reveal and hit rectangles come from the presenter. */
class MinecraftWidgetEncoder {
    fun encode(frame: WidgetFrame, bundle: AssetBundle): EncodedFrame {
        val chrome = frame.chrome()
        val prototype = chrome != null
        if (frame.fontPin() != (if (prototype) bundle.hudMetrics().pin() else bundle.pin())) {
            throw failure("FONT_PIN_MISMATCH")
        }
        if (frame.widgets().size > 32) throw failure("WIDGET_LIMIT")

        val bounds = frame.canvasBounds()
        var bottom = chrome?.baseline ?: (bounds.y + bounds.height)
        val width = bounds.width
        // A tall answer column still needs an encodable composite baseline, even
        // though world panels are drawn from their independent local components.
        if (!prototype && frame.scrollbar()?.buttons()?.isNotEmpty() == true) {
            bottom = min(bottom, min(192.0, bounds.y + 256.0))
        }

        val hud = Canvas(bundle, prototype)
        if (chrome != null) hud.chrome(chrome, bounds.x)
        val widgets = ArrayList<EncodedFrame.EncodedWidget>()
        for (widget in frame.widgets()) {
            validate(widget.bounds)
            val front = Canvas(bundle, prototype)
            val back = Canvas(bundle, prototype)
            draw(widget, frame.theme(), front, 0.0, 0.0, 0.0, letters = true, panels = !prototype)
            draw(widget, frame.theme(), back, 0.0, 0.0, 0.0, letters = false, panels = !prototype)
            front.move(widget.bounds.width)
            back.move(widget.bounds.width)
            draw(
                widget,
                frame.theme(),
                hud,
                widget.bounds.x - bounds.x,
                widget.bounds.y,
                bottom,
                letters = true,
                panels = !prototype,
            )
            widgets +=
                EncodedFrame.EncodedWidget(
                    widget.id,
                    widget.kind,
                    front.component,
                    back.component,
                    widget.bounds,
                    widget.choiceId,
                    widget.enabled,
                    widget.visualScale,
                    widget.transition,
                )
        }

        frame.scrollbar()?.let { scrollbar ->
            val track = scrollbar.track()
            validate(track)
            if (track.height > 192.0) throw failure("UNSUPPORTED_RASTER")
            hud.scrollbar(scrollbar, bounds.x, bottom, frame.theme())
            hud.navigation(scrollbar, bounds.x, bottom, frame.theme())

            var left = track.x
            var top = track.y
            var right = track.x + track.width
            var end = track.y + track.height
            for (button in scrollbar.buttons()) {
                left = kotlin.math.min(left, button.bounds.x)
                top = kotlin.math.min(top, button.bounds.y)
                right = kotlin.math.max(right, button.bounds.x + button.bounds.width)
                end = kotlin.math.max(end, button.bounds.y + button.bounds.height)
            }
            val outer = Rect(left, top, right - left, end - top)
            validate(outer)
            val paintBaseline = 192.0 + track.y - outer.y
            val local = Canvas(bundle, false)
            val back = Canvas(bundle, false)
            local.scrollbar(scrollbar, left, paintBaseline + top, frame.theme())
            local.navigation(scrollbar, left, paintBaseline + top, frame.theme())

            // The back entity turns 180 degrees. Reflect its asymmetric ink inside
            // the full component width so both faces occupy the same world track.
            val thumb = scrollbar.thumb()
            val backTrack =
                Rect(
                    left + outer.width - (track.x - left) - track.width,
                    track.y,
                    track.width,
                    track.height,
                )
            val backThumb =
                Rect(
                    left + outer.width - (thumb.x - left) - thumb.width,
                    thumb.y,
                    thumb.width,
                    thumb.height,
                )
            back.scrollbar(
                WidgetFrame.Scrollbar(backTrack, backThumb),
                left,
                paintBaseline + top,
                frame.theme(),
            )
            local.move(outer.width)
            back.move(outer.width)
            widgets +=
                EncodedFrame.EncodedWidget(
                    "scrollbar",
                    Kind.SCROLLBAR,
                    local.component,
                    back.component,
                    outer,
                    null,
                    false,
                    1.0,
                    Duration.ZERO,
                    paintBaseline,
                )
        }

        if (widgets.size > 32) throw failure("WIDGET_LIMIT")
        hud.move(width)
        var bytes = wireBytes(hud.component)
        for (widget in widgets) {
            bytes += wireBytes(widget.front) + wireBytes(widget.back)
            if (bytes > 65_536) throw failure("FRAME_LIMIT")
        }
        return EncodedFrame(widgets, hud.component)
    }

    private class Canvas(val assets: AssetBundle, val prototype: Boolean) {
        val metrics: FontMetrics = if (prototype) assets.hudMetrics() else assets.metrics()
        var component: Component = Component.empty().decoration(TextDecoration.ITALIC, false)
        var cursor = 0.0

        fun chrome(chrome: WidgetFrame.Chrome, left: Double) {
            val key = chrome.key
            if (!assets.hasEntry("assets/dialogue/font/$key.json")) throw failure("MISSING_GLYPH")
            val width = chrome.bounds.width / 2.0
            val x = chrome.bounds.x - left
            val fallbackAdvance = round(width).toInt() + 1
            draw(
                x,
                "\ue600",
                Key.key("dialogue:$key"),
                assets.hudBackdropAdvance(key, 0).orElse(fallbackAdvance).toDouble(),
                0xffffff,
                shadow = false,
            )
            draw(
                x + width,
                "\ue601",
                Key.key("dialogue:$key"),
                assets.hudBackdropAdvance(key, 1).orElse(fallbackAdvance).toDouble(),
                0xffffff,
                shadow = false,
            )
        }

        fun scrollbar(
            scrollbar: WidgetFrame.Scrollbar,
            left: Double,
            baseline: Double,
            theme: DialogueTheme,
        ) {
            val track = scrollbar.track()
            val thumb = scrollbar.thumb()
            if (track.width != 2.0) throw failure("UNSUPPORTED_RASTER")
            bar(track.x - left, track.y, thumb.y - track.y, baseline, 0x52606d)
            bar(
                thumb.x - left,
                thumb.y,
                thumb.height,
                baseline,
                colour(theme, "selected", 0xffcd75),
            )
            bar(
                track.x - left,
                thumb.y + thumb.height,
                track.y + track.height - thumb.y - thumb.height,
                baseline,
                0x52606d,
            )
        }

        fun navigation(
            scrollbar: WidgetFrame.Scrollbar,
            left: Double,
            baseline: Double,
            theme: DialogueTheme,
        ) {
            for (button in scrollbar.buttons()) {
                val glyph =
                    if (button.direction == NavigationDirection.PREVIOUS) "\ue780" else "\ue781"
                val advance = 17.0 // 16 ink pixels plus BitmapProvider's native spacing.
                draw(
                    button.bounds.x - left + (button.bounds.width - 16.0) / 2.0,
                    glyph,
                    font(baseline - button.bounds.y - (button.bounds.height - 8.0) / 2.0),
                    advance,
                    if (button.enabled()) colour(theme, "selected", 0xffcd75) else 0x52606d,
                    shadow = true,
                )
            }
        }

        fun bar(x: Double, initialY: Double, initialHeight: Double, baseline: Double, colour: Int) {
            var y = initialY
            var height = initialHeight
            if (height < 0.0 || height != Math.rint(height)) throw failure("UNSUPPORTED_RASTER")
            while (height > 0.0) {
                val part = min(height, 128.0)
                barGlyph(x, y, part, baseline, colour)
                y += part
                height -= part
            }
        }

        fun barGlyph(x: Double, y: Double, height: Double, baseline: Double, colour: Int) {
            val ascent = baseline - y
            val key = "hud_scroll_a_${ascent.toInt()}"
            if (
                height < 1.0 ||
                    height > 128.0 ||
                    height != Math.rint(height) ||
                    ascent != Math.rint(ascent) ||
                    !assets.hasEntry("assets/dialogue/font/$key.json")
            ) {
                throw failure("UNSUPPORTED_RASTER")
            }
            draw(
                x,
                Character.toString(0xe700 + height.toInt() - 1),
                Key.key("dialogue:$key"),
                3.0,
                colour,
                shadow = false,
            )
        }

        fun font(ascent: Double): Key {
            val key = (if (prototype) "hud_a_" else "a_") + ascent.toInt()
            if (ascent != Math.rint(ascent) || !assets.hasEntry("assets/dialogue/font/$key.json")) {
                throw failure("UNSUPPORTED_RASTER")
            }
            return Key.key("dialogue:$key")
        }

        fun move(target: Double) {
            val distance = target - cursor
            if (distance == 0.0) return
            val doubled = distance * 2.0
            if (doubled != Math.rint(doubled) || abs(distance) > 32_767.0) {
                throw failure("UNSUPPORTED_RASTER")
            }
            val whole = abs(distance).toInt()
            val glyphs = StringBuilder()
            for (index in 0 until 16) {
                if (whole and (1 shl index) != 0) {
                    glyphs.appendCodePoint((if (distance > 0.0) 0xf810 else 0xf830) + index)
                }
            }
            if (abs(distance) - whole != 0.0) {
                glyphs.appendCodePoint(if (distance > 0.0) 0xf801 else 0xf802)
            }
            component =
                component.append(
                    Component.text(glyphs.toString())
                        .font(Key.key("dialogue:spacing"))
                        .shadowColor(ShadowColor.none())
                )
            cursor = target
        }

        fun draw(
            x: Double,
            text: String,
            font: Key,
            advance: Double,
            colour: Int,
            shadow: Boolean,
        ) {
            move(x)
            var part: Component = Component.text(text).font(font).color(TextColor.color(colour))
            part =
                part.shadowColor(
                    if (shadow) ShadowColor.shadowColor(0xff000000.toInt()) else ShadowColor.none()
                )
            component = component.append(part)
            cursor = x + advance
        }

        fun panel(x: Double, y: Double, width: Int, height: Int, baseline: Double, style: Int) {
            val heightIndex =
                when (height) {
                    28 -> 0
                    40 -> 1
                    56 -> 2
                    72 -> 3
                    24 -> 4
                    32 -> 5
                    48 -> 6
                    64 -> 7
                    else -> throw failure("UNSUPPORTED_RASTER")
                }
            if (width < 8) throw failure("UNSUPPORTED_RASTER")
            val base = 0xe400 + style * 48 + heightIndex * 6
            tile(x, y, baseline, base, 4)
            var remaining = width - 8
            var pen = x + 4.0
            for (bit in 3 downTo 0) {
                val size = 1 shl bit
                val count = remaining / size
                if (count > 0) {
                    val glyphs =
                        (Character.toString(base + 2 + bit) + Character.toString(0xf800)).repeat(
                            count
                        )
                    draw(
                        pen,
                        glyphs,
                        font(baseline - y),
                        (size * count).toDouble(),
                        0xffffff,
                        shadow = false,
                    )
                    pen += (size * count).toDouble()
                    remaining -= size * count
                }
            }
            tile(x + width - 4.0, y, baseline, base + 1, 4)
        }

        fun tile(x: Double, y: Double, baseline: Double, code: Int, width: Int) {
            // BitmapProvider's full-ink tile advance is width+1; the next explicit move removes it.
            draw(
                x,
                Character.toString(code),
                font(baseline - y),
                (width + 1).toDouble(),
                0xffffff,
                shadow = false,
            )
        }
    }

    private companion object {
        private fun wireBytes(component: Component): Long =
            GsonComponentSerializer.gson()
                .serialize(component)
                .toByteArray(StandardCharsets.UTF_8)
                .size
                .toLong()

        private fun validate(rect: Rect) {
            if (
                rect.width > 512.0 ||
                    rect.height > 256.0 ||
                    rect.width != Math.rint(rect.width) ||
                    rect.height != Math.rint(rect.height)
            ) {
                throw failure("UNSUPPORTED_RASTER")
            }
        }

        private fun failure(code: String) = DialoguePresenter.FallbackRequired(code)

        private fun colour(theme: DialogueTheme, key: String, fallback: Int): Int =
            theme.colours().getOrDefault(key, fallback)

        private fun draw(
            widget: Widget,
            theme: DialogueTheme,
            canvas: Canvas,
            offsetX: Double,
            offsetY: Double,
            baseline: Double,
            letters: Boolean,
            panels: Boolean,
        ) {
            val panel = panels && widget.kind != Kind.HINT
            val width = widget.bounds.width
            val height = widget.bounds.height
            if (panel) {
                canvas.panel(
                    offsetX,
                    offsetY,
                    width.toInt(),
                    height.toInt(),
                    baseline,
                    if (!letters) 2
                    else if (widget.selected || widget.kind == Kind.SPEAKER) 1 else 0,
                )
            }
            if (!letters) return

            val lines = widget.layout.visible(widget.visibleGraphemes)
            val x = offsetX + widget.textBounds.x - widget.bounds.x
            val y = offsetY + widget.textBounds.y - widget.bounds.y
            for (index in lines.indices) {
                val text = lines[index]
                val advance =
                    widget.layout
                        .lines()[index]
                        .advances()
                        .stream()
                        .limit(Graphemes.split(text).size.toLong())
                        .mapToDouble(Double::toDouble)
                        .sum()
                canvas.draw(
                    x,
                    text,
                    canvas.font(baseline - y - index * canvas.metrics.lineHeight()),
                    advance,
                    colour(
                        theme,
                        if (!panels) {
                            if (
                                widget.kind == Kind.SPEAKER ||
                                    widget.kind == Kind.CHOICE && widget.selected
                            ) {
                                "selected"
                            } else if (widget.kind == Kind.CHOICE) {
                                "muted"
                            } else if (widget.kind == Kind.HINT) {
                                "hint"
                            } else {
                                "text"
                            }
                        } else if (widget.enabled) {
                            "text"
                        } else {
                            "muted"
                        },
                        0xf3eee4,
                    ),
                    shadow = true,
                )
            }
        }
    }
}
