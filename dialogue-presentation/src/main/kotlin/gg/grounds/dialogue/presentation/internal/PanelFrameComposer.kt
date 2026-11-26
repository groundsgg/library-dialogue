package gg.grounds.dialogue.presentation.internal

import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.text.Graphemes
import gg.grounds.dialogue.core.text.ResolvedText
import gg.grounds.dialogue.presentation.ChoiceViewport
import gg.grounds.dialogue.presentation.DialoguePresenter.SurfaceProfile
import gg.grounds.dialogue.presentation.LayoutProfile
import gg.grounds.dialogue.presentation.UiLabelSource
import gg.grounds.dialogue.presentation.WidgetFrame
import gg.grounds.dialogue.presentation.WidgetFrame.Kind
import gg.grounds.dialogue.presentation.WidgetFrame.Rect
import gg.grounds.dialogue.presentation.WidgetFrame.Widget
import kotlin.math.ceil
import kotlin.math.max

/** Lays out generic PANELS; selection growth leaves the choice hit rectangles unchanged. */
internal object PanelFrameComposer {
    fun compose(
        view: DialogueView,
        profile: SurfaceProfile,
        layout: LayoutProfile,
        preferences: PresentationPreferences,
        ui: UiLabelSource.UiLabels,
        choiceStart: Int?,
    ): WidgetFrame {
        val metrics = requireNotNull(profile.metrics)
        val widgets = ArrayList<Widget>()
        val text = ResolvedText(view.text(), view.localeTag())
        val body = layout(text, metrics, layout.width - 2 * layout.padding, layout.maxTextLines)
        val bodyHeight = body.height() + 2 * layout.padding
        widgets +=
            widget(
                "text",
                Kind.TEXT,
                Rect(0.0, 0.0, layout.width, bodyHeight),
                text,
                body,
                layout.padding,
                layout.padding,
                view.revealedGraphemes(),
                false,
                true,
                null,
                profile,
                preferences,
            )
        val name = ResolvedText(view.speaker(), view.localeTag())
        val nameLayout = layout(name, metrics, layout.width - 2 * layout.padding, 1)
        val nameWidth = max(nameLayout.width() + 2 * layout.padding, 40.0)
        val nameHeight = metrics.lineHeight() + layout.padding
        val nameX = (layout.width - nameWidth) / 2
        val nameY = -nameHeight - layout.gap
        widgets +=
            widget(
                "speaker",
                Kind.SPEAKER,
                Rect(nameX, nameY, nameWidth, nameHeight),
                name,
                nameLayout,
                (nameWidth - nameLayout.width()) / 2,
                (nameHeight - nameLayout.height()) / 2,
                Graphemes.split(name.plain).size,
                false,
                true,
                null,
                profile,
                preferences,
            )
        val window =
            ChoiceViewport()
                .window(view.choices(), view.selectedChoiceId(), layout.choiceCapacity, choiceStart)
        val choiceLabels =
            window.choices().map { choice ->
                val reason =
                    if (choice.availability == DialogueView.Availability.AVAILABLE) ""
                    else ui.choiceReasons().getOrDefault(choice.id, "")
                ResolvedText(
                    choice.label + if (reason.isEmpty()) "" else "\n$reason",
                    view.localeTag(),
                )
            }
        val choiceLayouts = choiceLabels.map {
            layout(
                it,
                metrics,
                layout.choiceWidth - 2 * layout.choicePadding,
                layout.maxChoiceLines,
            )
        }
        var maxLabelHeight = choiceLayouts.maxOfOrNull { it.height() } ?: metrics.lineHeight()
        if (layout.navigationButtons) {
            for (choice in view.choices()) {
                val reason =
                    if (choice.availability == DialogueView.Availability.AVAILABLE) ""
                    else ui.choiceReasons().getOrDefault(choice.id, "")
                val label =
                    ResolvedText(
                        choice.label + if (reason.isEmpty()) "" else "\n$reason",
                        view.localeTag(),
                    )
                maxLabelHeight =
                    max(
                        maxLabelHeight,
                        layout(
                                label,
                                metrics,
                                layout.choiceWidth - 2 * layout.choicePadding,
                                layout.maxChoiceLines,
                            )
                            .height(),
                    )
            }
        }
        val cardHeight = maxLabelHeight + 2 * layout.choicePadding
        val growth =
            ceil(layout.choiceWidth * (requireNotNull(profile.theme).selectedScale() - 1) / 2)
        val columnGap = layout.columnGap + growth
        val x =
            when (layout.placement) {
                LayoutProfile.ChoicePlacement.BELOW -> (layout.width - layout.choiceWidth) / 2
                LayoutProfile.ChoicePlacement.RIGHT -> layout.width + columnGap
                LayoutProfile.ChoicePlacement.LEFT -> -layout.choiceWidth - columnGap
            }
        val y =
            if (layout.placement != LayoutProfile.ChoicePlacement.BELOW) 0.0
            else bodyHeight + layout.gap
        window.choices().forEachIndexed { index, choice ->
            val bounds =
                Rect(x, y + index * (cardHeight + layout.gap), layout.choiceWidth, cardHeight)
            widgets +=
                widget(
                    "choice:${choice.id}",
                    Kind.CHOICE,
                    bounds,
                    choiceLabels[index],
                    choiceLayouts[index],
                    layout.choicePadding,
                    (cardHeight - choiceLayouts[index].height()) / 2,
                    Graphemes.split(choiceLabels[index].plain).size,
                    choice.id == view.selectedChoiceId(),
                    choice.availability == DialogueView.Availability.AVAILABLE,
                    choice.id,
                    profile,
                    preferences,
                )
        }
        val hintText = ResolvedText(ui.hint(), view.localeTag())
        val hintWidth =
            if (layout.placement != LayoutProfile.ChoicePlacement.BELOW) layout.choiceWidth
            else layout.width
        val hintLayout = layout(hintText, metrics, hintWidth, 2)
        val hintX = if (layout.placement != LayoutProfile.ChoicePlacement.BELOW) x else 0.0
        val hintY =
            if (layout.placement != LayoutProfile.ChoicePlacement.BELOW) {
                -hintLayout.height() -
                    layout.gap -
                    if (layout.navigationButtons && window.total() > layout.choiceCapacity) 28
                    else 0
            } else {
                y + window.choices().size * (cardHeight + layout.gap) + layout.gap
            }
        widgets +=
            widget(
                "hint",
                Kind.HINT,
                Rect(hintX, hintY, hintWidth, hintLayout.height()),
                hintText,
                hintLayout,
                0.0,
                0.0,
                Graphemes.split(ui.hint()).size,
                false,
                true,
                null,
                profile,
                preferences,
            )
        if (ui.notice().isNotEmpty()) {
            val statusText = ResolvedText(ui.notice(), view.localeTag())
            val statusLayout = layout(statusText, metrics, hintWidth, 2)
            val statusY =
                if (layout.placement != LayoutProfile.ChoicePlacement.BELOW) {
                    y + window.choices().size * (cardHeight + layout.gap) + layout.gap
                } else hintY + hintLayout.height() + layout.gap
            widgets +=
                widget(
                    "status",
                    Kind.HINT,
                    Rect(hintX, statusY, hintWidth, statusLayout.height()),
                    statusText,
                    statusLayout,
                    0.0,
                    0.0,
                    Graphemes.split(statusText.plain).size,
                    false,
                    true,
                    null,
                    profile,
                    preferences,
                )
        }
        var scroll =
            if (window.total() > layout.choiceCapacity) {
                scrollbar(
                    window,
                    Rect(
                        x + layout.choiceWidth + growth + 8,
                        y,
                        2.0,
                        layout.choiceCapacity * cardHeight +
                            (layout.choiceCapacity - 1) * layout.gap,
                    ),
                )
            } else null
        if (scroll != null && layout.navigationButtons) {
            val track = scroll.track()
            val buttons =
                listOf(
                    WidgetFrame.NavigationButton.page(
                        WidgetFrame.NavigationDirection.PREVIOUS,
                        Rect(x + (layout.choiceWidth - 64) / 2, track.y - 28, 64.0, 24.0),
                        if (window.start() > 0) window.start() - layout.choiceCapacity else null,
                    ),
                    WidgetFrame.NavigationButton.page(
                        WidgetFrame.NavigationDirection.NEXT,
                        Rect(
                            x + (layout.choiceWidth - 64) / 2,
                            track.y + track.height + 4,
                            64.0,
                            24.0,
                        ),
                        if (window.start() + layout.choiceCapacity < window.total())
                            window.start() + layout.choiceCapacity
                        else null,
                    ),
                )
            scroll = WidgetFrame.Scrollbar(track, scroll.thumb(), buttons)
        }
        return WidgetFrame(
            view,
            widgets,
            metrics.pin(),
            requireNotNull(profile.theme),
            null,
            scroll,
        )
    }
}
