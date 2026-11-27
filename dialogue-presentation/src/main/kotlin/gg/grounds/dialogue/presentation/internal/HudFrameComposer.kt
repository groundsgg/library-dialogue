package gg.grounds.dialogue.presentation.internal

import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.text.Graphemes
import gg.grounds.dialogue.core.text.ResolvedText
import gg.grounds.dialogue.presentation.ChoiceViewport
import gg.grounds.dialogue.presentation.DialoguePresenter.FallbackRequired
import gg.grounds.dialogue.presentation.FontMetrics
import gg.grounds.dialogue.presentation.LayoutProfile
import gg.grounds.dialogue.presentation.TextLayout
import gg.grounds.dialogue.presentation.WidgetFrame
import gg.grounds.dialogue.presentation.WidgetFrame.Kind
import gg.grounds.dialogue.presentation.WidgetFrame.Rect
import gg.grounds.dialogue.presentation.WidgetFrame.Widget
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Preserves the prototype HUD's fixed ten-pixel raster and its chrome geometry. */
internal object HudFrameComposer {
    fun compose(input: GraphicalFrameInput): WidgetFrame {
        val view = input.view
        val layout = input.layout
        val metrics = input.metrics
        val ui = input.ui
        if (
            metrics.lineHeight() != 10.0 ||
                layout.choiceCapacity != 3 ||
                layout.maxTextLines > 3 ||
                layout.maxChoiceLines > 2
        )
            throw FallbackRequired("UNSUPPORTED_RASTER")
        val widgets = ArrayList<Widget>()
        val body = ResolvedText(view.text(), view.localeTag())
        val bodyLayout = layout(body, metrics, layout.width - 18, 3)
        widgets +=
            widget(
                "text",
                Kind.TEXT,
                Rect(0.0, 0.0, layout.width, 48.0),
                body,
                bodyLayout,
                9.0,
                17.0,
                view.revealedGraphemes(),
                false,
                true,
                null,
                input.theme,
                input.transition,
            )
        val name = ResolvedText(view.speaker(), view.localeTag())
        val nameLayout = layout(name, metrics, layout.width - 18, 1)
        widgets +=
            widget(
                "speaker",
                Kind.SPEAKER,
                Rect(9.0, 7.0, layout.width - 18, 10.0),
                name,
                nameLayout,
                0.0,
                0.0,
                Graphemes.split(name.plain).size,
                false,
                true,
                null,
                input.theme,
                input.transition,
            )
        val window = ChoiceViewport().window(view.choices(), view.selectedChoiceId(), 3)
        val choiceLabels = ArrayList<ResolvedText>()
        val lines = ArrayList<TextLayout.TextLayoutResult>()
        for (choice in window.choices()) {
            val reason =
                if (choice.availability == DialogueView.Availability.AVAILABLE) ""
                else ui.choiceReasons().getOrDefault(choice.id, "")
            val prefix = if (choice.id == view.selectedChoiceId()) "> " else "  "
            val text =
                ResolvedText(
                    prefix + choice.label + if (reason.isEmpty()) "" else "\n  $reason",
                    view.localeTag(),
                )
            choiceLabels += text
            lines += layout(text, metrics, layout.choiceWidth, 2)
        }
        var rowHeight = lines.maxOfOrNull { it.height() } ?: 10.0
        val below = layout.placement == LayoutProfile.ChoicePlacement.BELOW
        val shared = layout.style == LayoutProfile.Style.HUD
        val cards = layout.style == LayoutProfile.Style.HUD_CARDS
        val count = window.choices().size
        if (cards) {
            for (choice in view.choices()) {
                val reason =
                    if (choice.availability == DialogueView.Availability.AVAILABLE) ""
                    else ui.choiceReasons().getOrDefault(choice.id, "")
                val label =
                    ResolvedText(
                        "> " + choice.label + if (reason.isEmpty()) "" else "\n  $reason",
                        view.localeTag(),
                    )
                rowHeight = max(rowHeight, layout(label, metrics, layout.choiceWidth, 2).height())
            }
        }
        val gap =
            if (cards && layout.placement == LayoutProfile.ChoicePlacement.LEFT) 15.0
            else layout.gap
        val columnWidth = layout.choiceWidth + 18
        val left =
            if (layout.placement == LayoutProfile.ChoicePlacement.LEFT) -columnWidth - gap else 0.0
        var width = if (below) layout.width else layout.width + gap + columnWidth
        if (cards && layout.placement != LayoutProfile.ChoicePlacement.LEFT) width += 9
        val step = if (cards) rowHeight + 13 else rowHeight
        val answerHeight = if (cards) 3 * (rowHeight + 10) + 6 else 3 * rowHeight + 14
        val height =
            if (shared) {
                if (below) 84 + 3 * (rowHeight - 10) else if (rowHeight == 10.0) 51.0 else 75.0
            } else if (below) 54 + answerHeight else max(48.0, answerHeight)
        val x =
            when (layout.placement) {
                LayoutProfile.ChoicePlacement.BELOW -> 9.0
                LayoutProfile.ChoicePlacement.RIGHT -> layout.width + gap + 9
                LayoutProfile.ChoicePlacement.LEFT -> left + 9
            }
        val y = if (below) if (shared) 50.0 else 61.0 else 7.0
        window.choices().forEachIndexed { index, choice ->
            val text = choiceLabels[index]
            val bounds =
                if (cards) {
                    Rect(x - 9, y - 7 + index * step, layout.choiceWidth + 18, rowHeight + 10)
                } else Rect(x, y + index * step, layout.choiceWidth, rowHeight)
            widgets +=
                widget(
                    "choice:${choice.id}",
                    Kind.CHOICE,
                    bounds,
                    text,
                    lines[index],
                    if (cards) 9.0 else 0.0,
                    if (cards) centeredInkInset(lines[index], metrics, bounds.height) else 0.0,
                    Graphemes.split(text.plain).size,
                    choice.id == view.selectedChoiceId(),
                    choice.availability == DialogueView.Availability.AVAILABLE,
                    choice.id,
                    input.theme,
                    input.transition,
                )
        }
        val hint = ResolvedText(ui.hint(), view.localeTag())
        val hintLayout = layout(hint, metrics, width - 18, 2)
        widgets +=
            widget(
                "hint",
                Kind.HINT,
                Rect(left + 9, height - 1, width - 18, hintLayout.height()),
                hint,
                hintLayout,
                0.0,
                0.0,
                Graphemes.split(hint.plain).size,
                false,
                true,
                null,
                input.theme,
                input.transition,
            )
        if (ui.notice().isNotEmpty()) {
            val notice = ResolvedText(ui.notice(), view.localeTag())
            val noticeLayout = layout(notice, metrics, width - 18, 2)
            widgets +=
                widget(
                    "status",
                    Kind.HINT,
                    Rect(
                        left + 9,
                        height - 1 + hintLayout.height() + 4,
                        width - 18,
                        noticeLayout.height(),
                    ),
                    notice,
                    noticeLayout,
                    0.0,
                    0.0,
                    Graphemes.split(notice.plain).size,
                    false,
                    true,
                    null,
                    input.theme,
                    input.transition,
                )
        }
        val chrome =
            WidgetFrame.Chrome(
                (if (shared) "hud_" else if (cards) "hud_cards_" else "hud_split_") +
                    layout.placement.name.lowercase(Locale.ROOT) +
                    "_" +
                    rowHeight.toInt() +
                    "_" +
                    if (cards) count else 3,
                Rect(left, 0.0, width, height),
                height + 10,
            )
        val scroll =
            if (window.total() > layout.choiceCapacity) {
                val trackHeight = if (cards) answerHeight else 3 * rowHeight
                val top = if (cards) y - 7 else y
                scrollbar(
                    window,
                    Rect(x + layout.choiceWidth + if (cards) 14 else 3, top, 2.0, trackHeight),
                )
            } else null
        return WidgetFrame(
            view,
            widgets,
            metrics.pin(),
            input.theme,
            chrome,
            scroll,
        )
    }

    private fun centeredInkInset(
        text: TextLayout.TextLayoutResult,
        metrics: FontMetrics,
        height: Double,
    ): Double {
        var top = Double.POSITIVE_INFINITY
        var bottom = Double.NEGATIVE_INFINITY
        text.lines().forEachIndexed { line, value ->
            value.graphemes().forEach { grapheme ->
                val ink = metrics.inkBounds(grapheme)
                if (ink.bottom > ink.top) {
                    top = min(top, line * metrics.lineHeight() + ink.top)
                    bottom = max(bottom, line * metrics.lineHeight() + ink.bottom)
                }
            }
        }
        return if (top.isFinite()) round(height / 2 - (top + bottom) / 2)
        else (height - text.height()) / 2
    }
}
