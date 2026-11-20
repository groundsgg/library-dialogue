package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.presentation.WidgetFrame
import java.time.Duration
import net.kyori.adventure.text.Component

/** Same encoded widgets feed worldspace surfaces; the screen surface uses the combined canvas. */
class EncodedFrame(widgets: List<EncodedWidget>, private val hud: Component) {
    private val widgets = java.util.List.copyOf(widgets)

    fun widgets(): List<EncodedWidget> = widgets

    fun hud(): Component = hud

    override fun equals(other: Any?): Boolean =
        other is EncodedFrame && widgets == other.widgets && hud == other.hud

    override fun hashCode(): Int = widgets.hashCode() * 31 + hud.hashCode()

    override fun toString(): String = "EncodedFrame[widgets=$widgets, hud=$hud]"

    @JvmRecord
    data class EncodedWidget(
        val id: String,
        val kind: WidgetFrame.Kind,
        val front: Component,
        val back: Component,
        val bounds: WidgetFrame.Rect,
        val choiceId: String?,
        val enabled: Boolean,
        val visualScale: Double,
        val transition: Duration,
        val paintBaseline: Double,
    ) {
        constructor(
            id: String,
            kind: WidgetFrame.Kind,
            front: Component,
            back: Component,
            bounds: WidgetFrame.Rect,
            choiceId: String?,
            enabled: Boolean,
            visualScale: Double,
            transition: Duration,
        ) : this(id, kind, front, back, bounds, choiceId, enabled, visualScale, transition, 0.0)
    }
}
