package gg.grounds.dialogue.presentation.internal

import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.text.ResolvedText
import gg.grounds.dialogue.core.validation.ContentFingerprint
import gg.grounds.dialogue.presentation.ChoiceViewport
import gg.grounds.dialogue.presentation.DialoguePresenter.FallbackRequired
import gg.grounds.dialogue.presentation.DialoguePresenter.SurfaceProfile
import gg.grounds.dialogue.presentation.FontMetrics
import gg.grounds.dialogue.presentation.LayoutProfile
import gg.grounds.dialogue.presentation.TextLayout
import gg.grounds.dialogue.presentation.WidgetFrame
import gg.grounds.dialogue.presentation.WidgetFrame.Kind
import gg.grounds.dialogue.presentation.WidgetFrame.Rect
import gg.grounds.dialogue.presentation.WidgetFrame.Widget
import java.time.Duration
import java.util.Locale
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
    profile: SurfaceProfile,
    preferences: PresentationPreferences,
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
        if (selected) requireNotNull(profile.theme).selectedScale() else 1.0,
        if (preferences.reducedMotion()) Duration.ZERO
        else requireNotNull(profile.theme).transition(),
        choiceId,
    )

/** Computes the same canonical profile pin for surface preparation regardless of layout style. */
internal fun profilePin(
    profile: SurfaceProfile,
    preferences: PresentationPreferences,
): String {
    if (profile.semanticOnly) return "semantic"
    val layoutProfile = profile.resolveLayout(preferences)
    val theme = requireNotNull(profile.theme)
    val layout = linkedMapOf<String, Any>()
    layout["key"] = layoutProfile.key
    if (layoutProfile.style != LayoutProfile.Style.PANELS)
        layout["style"] = layoutProfile.style.name
    layout["width"] = layoutProfile.width
    layout["choiceWidth"] = layoutProfile.choiceWidth
    layout["padding"] = layoutProfile.padding
    if (layoutProfile.choicePadding != layoutProfile.padding)
        layout["choicePadding"] = layoutProfile.choicePadding
    layout["maxTextLines"] = layoutProfile.maxTextLines
    layout["choiceCapacity"] = layoutProfile.choiceCapacity
    layout["maxChoiceLines"] = layoutProfile.maxChoiceLines
    layout["placement"] = layoutProfile.placement.name.lowercase(Locale.ROOT)
    layout["gap"] = layoutProfile.gap
    if (layoutProfile.columnGap != layoutProfile.gap) layout["columnGap"] = layoutProfile.columnGap
    if (layoutProfile.navigationButtons) layout["navigationButtons"] = true
    return ContentFingerprint.hash(
            ContentFingerprint.canonicalBytes(
                mapOf(
                    "layout" to layout,
                    "theme" to
                        mapOf(
                            "id" to theme.id(),
                            "colours" to theme.colours(),
                            "selectedScale" to theme.selectedScale(),
                            "transitionNanos" to theme.transition().toNanos(),
                        ),
                    "font" to requireNotNull(profile.metrics).pin(),
                )
            )
        )
        .sha256
}
