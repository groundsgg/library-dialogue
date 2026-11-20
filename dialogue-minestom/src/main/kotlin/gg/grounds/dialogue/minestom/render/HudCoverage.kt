package gg.grounds.dialogue.minestom.render

import kotlin.math.abs
import kotlin.math.round
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor

/** Spatial fade over the ordinary bottom HUD area; no game-state or world-time mutation. */
class HudCoverage private constructor() {
    companion object {
        @JvmStatic fun fadeGuiPixels(): Int = 128

        @JvmStatic
        fun compose(hud: Component, canvasWidth: Double, visible: Boolean): Component {
            if (!visible) return hud
            val width = 256 * 128
            val start = (canvasWidth - width) / 2
            return shift(start)
                .append(
                    Component.text("\ue200\uf800".repeat(128))
                        .font(Key.key("dialogue:mask"))
                        .color(TextColor.color(0xffffff))
                        .shadowColor(ShadowColor.none())
                )
                .append(shift(-start - width))
                .append(hud)
        }

        private fun shift(pixels: Double): Component {
            val amount = abs(pixels).toInt()
            require(abs(pixels) <= 32767 && pixels * 2 == round(pixels * 2)) {
                "UNSUPPORTED_RASTER"
            }
            val value = StringBuilder()
            for (index in 0 until 16) {
                if (amount and (1 shl index) != 0)
                    value.appendCodePoint((if (pixels >= 0) 0xf810 else 0xf830) + index)
            }
            if (abs(pixels) - amount != 0.0)
                value.appendCodePoint(if (pixels >= 0) 0xf801 else 0xf802)
            return Component.text(value.toString())
                .font(Key.key("dialogue:spacing"))
                .shadowColor(ShadowColor.none())
        }
    }
}
