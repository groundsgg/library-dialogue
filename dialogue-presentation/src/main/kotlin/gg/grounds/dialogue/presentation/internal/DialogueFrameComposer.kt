package gg.grounds.dialogue.presentation.internal

import gg.grounds.dialogue.presentation.LayoutProfile
import gg.grounds.dialogue.presentation.WidgetFrame

/** Routes already-resolved graphical inputs without owning callbacks, session state or time. */
internal object DialogueFrameComposer {
    fun compose(input: GraphicalFrameInput): WidgetFrame =
        if (input.layout.style != LayoutProfile.Style.PANELS) HudFrameComposer.compose(input)
        else PanelFrameComposer.compose(input)
}
