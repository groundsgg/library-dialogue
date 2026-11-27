package gg.grounds.dialogue.presentation.internal

import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.presentation.DialogueTheme
import gg.grounds.dialogue.presentation.FontMetrics
import gg.grounds.dialogue.presentation.LayoutProfile
import gg.grounds.dialogue.presentation.UiLabelSource.UiLabels
import java.time.Duration

/** Resolved graphical policy and labels; font measurements retain their normal lookup semantics. */
internal data class GraphicalFrameInput(
    val view: DialogueView,
    val layout: LayoutProfile,
    val theme: DialogueTheme,
    val metrics: FontMetrics,
    val ui: UiLabels,
    val transition: Duration,
    val choiceStart: Int?,
)
