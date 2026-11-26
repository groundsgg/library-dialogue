package gg.grounds.dialogue.presentation.internal

import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.presentation.DialoguePresenter.Controls
import gg.grounds.dialogue.presentation.DialoguePresenter.SurfaceProfile
import gg.grounds.dialogue.presentation.LayoutProfile
import gg.grounds.dialogue.presentation.UiLabelSource
import gg.grounds.dialogue.presentation.WidgetFrame

/**
 * Resolves labels and routes immutable view/profile inputs without owning session state or time.
 */
internal object DialogueFrameComposer {
    fun compose(
        view: DialogueView,
        profile: SurfaceProfile,
        preferences: PresentationPreferences,
        controls: Controls,
        labels: UiLabelSource,
        choiceStart: Int?,
    ): WidgetFrame {
        if (profile.semanticOnly) return WidgetFrame(view, emptyList(), "semantic", "semantic")
        val layout = profile.resolveLayout(preferences)
        val metrics = requireNotNull(profile.metrics)
        val reasons = linkedMapOf<String, String>()
        view
            .choices()
            .filter { it.availability != DialogueView.Availability.AVAILABLE }
            .forEach { reasons[it.id] = checkNotNull(it.unavailableReason) }
        val ui =
            requireNotNull(
                labels.resolve(
                    UiLabelSource.LabelRequest(
                        view.localeTag(),
                        view.hint(),
                        controls,
                        view.notice(),
                        reasons,
                        metrics.supportsControlGlyphs(),
                    )
                )
            )
        if (layout.style != LayoutProfile.Style.PANELS) {
            return HudFrameComposer.compose(view, profile, layout, preferences, ui)
        }
        return PanelFrameComposer.compose(view, profile, layout, preferences, ui, choiceStart)
    }
}
