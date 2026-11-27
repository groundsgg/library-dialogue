package gg.grounds.dialogue.presentation.internal

import gg.grounds.dialogue.core.text.ResolvedText
import gg.grounds.dialogue.presentation.ChoiceViewport
import gg.grounds.dialogue.presentation.DialoguePresenter.FallbackRequired
import gg.grounds.dialogue.presentation.DialogueTheme
import gg.grounds.dialogue.presentation.FontMetrics
import gg.grounds.dialogue.presentation.TextLayout
import gg.grounds.dialogue.presentation.WidgetFrame
import gg.grounds.dialogue.presentation.WidgetFrame.Kind
import gg.grounds.dialogue.presentation.WidgetFrame.Rect
import gg.grounds.dialogue.presentation.WidgetFrame.Widget
import java.time.Duration
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Maps the visible choice window onto a pixel-rounded track, anchoring its final thumb. */
internal fun scrollbar(
    window: ChoiceViewport.ChoiceWindow,
    track: Rect,
): WidgetFrame.Scrollbar {
    val thumbHeight = max(6.0, floor(track.height * window.choices().size / window.total()))
    val offset =
        if (window.start() + window.choices().size == window.total()) track.height - thumbHeight
        else
            min(
                track.height - thumbHeight,
                round(track.height * window.start() / window.total()),
            )
    return WidgetFrame.Scrollbar(
        track,
        Rect(track.x, track.y + offset, track.width, thumbHeight),
    )
}

/** Keeps text overflow and missing-glyph failures in the presenter fallback contract. */
internal fun layout(
    text: ResolvedText,
    metrics: FontMetrics,
    width: Double,
    lines: Int,
): TextLayout.TextLayoutResult {
    val result = TextLayout().layout(text, metrics, TextLayout.TextLimits(width, lines))
    if (!result.isValid()) throw FallbackRequired(requireNotNull(result.problem()))
    return result
}

/** Builds a fixed hit rectangle with independent text insets and selection visuals. */
internal fun widget(
    id: String,
    kind: Kind,
    bounds: Rect,
    text: ResolvedText,
    layout: TextLayout.TextLayoutResult,
    dx: Double,
    dy: Double,
    revealed: Int,
    selected: Boolean,
    enabled: Boolean,
    choiceId: String?,
    theme: DialogueTheme,
    transition: Duration,
): Widget =
    Widget(
        id,
        kind,
        bounds,
        Rect(bounds.x + dx, bounds.y + dy, layout.width(), layout.height()),
        text,
        layout,
        revealed,
        selected,
        enabled,
        if (selected) theme.selectedScale() else 1.0,
        transition,
        choiceId,
    )
