package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.runtime.DialogueView
import java.util.Locale

/** Synchronous projection from already loaded host translations; all output is plain text. */
fun interface UiLabelSource {
    class LabelRequest
    @JvmOverloads
    constructor(
        private val localeTag: String,
        private val phase: DialogueView.HintPhase,
        private val controls: DialoguePresenter.Controls,
        private val notice: String,
        unavailableReasons: Map<String, String>,
        private val controlIcons: Boolean = false,
    ) {
        private val unavailableReasons = java.util.Map.copyOf(unavailableReasons)

        fun controlIcons(): Boolean = controlIcons

        fun localeTag(): String = localeTag

        fun phase(): DialogueView.HintPhase = phase

        fun controls(): DialoguePresenter.Controls = controls

        fun notice(): String = notice

        fun unavailableReasons(): Map<String, String> = unavailableReasons

        override fun equals(other: Any?): Boolean =
            other is LabelRequest &&
                localeTag == other.localeTag &&
                phase == other.phase &&
                controls == other.controls &&
                notice == other.notice &&
                unavailableReasons == other.unavailableReasons &&
                controlIcons == other.controlIcons

        override fun hashCode(): Int =
            ((((localeTag.hashCode() * 31 + phase.hashCode()) * 31 + controls.hashCode()) * 31 +
                notice.hashCode()) * 31 + unavailableReasons.hashCode()) * 31 +
                controlIcons.hashCode()

        override fun toString(): String =
            "LabelRequest[localeTag=$localeTag, phase=$phase, controls=$controls, notice=$notice, unavailableReasons=$unavailableReasons, controlIcons=$controlIcons]"
    }

    class UiLabels(hint: String, notice: String, choiceReasons: Map<String, String>) {
        private val hint = hint
        private val notice = notice
        private val choiceReasons = java.util.Map.copyOf(choiceReasons)

        fun hint(): String = hint

        fun notice(): String = notice

        fun choiceReasons(): Map<String, String> = choiceReasons

        override fun equals(other: Any?): Boolean =
            other is UiLabels &&
                hint == other.hint &&
                notice == other.notice &&
                choiceReasons == other.choiceReasons

        override fun hashCode(): Int =
            (hint.hashCode() * 31 + notice.hashCode()) * 31 + choiceReasons.hashCode()

        override fun toString(): String =
            "UiLabels[hint=$hint, notice=$notice, choiceReasons=$choiceReasons]"
    }

    fun resolve(request: LabelRequest): UiLabels

    companion object {
        @JvmStatic
        fun defaults(): UiLabelSource = UiLabelSource { request ->
            val de = Locale.forLanguageTag(request.localeTag()).language == "de"
            val action =
                if (request.controlIcons())
                    when (request.controls().confirm) {
                        DialoguePresenter.ConfirmAction.ATTACK -> ControlGlyphs.ATTACK
                        DialoguePresenter.ConfirmAction.USE -> ControlGlyphs.USE
                        DialoguePresenter.ConfirmAction.SWAP_HANDS -> ControlGlyphs.SWAP_HANDS
                    }
                else
                    when (request.controls().confirm) {
                        DialoguePresenter.ConfirmAction.ATTACK -> if (de) "Angreifen" else "Attack"
                        DialoguePresenter.ConfirmAction.USE -> if (de) "Benutzen" else "Use"
                        DialoguePresenter.ConfirmAction.SWAP_HANDS ->
                            if (de) "Offhandwechsel" else "Swap hands"
                    }
            val verb =
                when (request.phase()) {
                    DialogueView.HintPhase.REVEAL -> if (de) "Text anzeigen" else "Reveal text"
                    DialogueView.HintPhase.CHOOSE -> if (de) "Auswählen" else "Select"
                    DialogueView.HintPhase.CONTINUE -> if (de) "Weiter" else "Continue"
                    DialogueView.HintPhase.FINISH -> if (de) "Beenden" else "End"
                    DialogueView.HintPhase.BUSY -> if (de) "Bitte warten" else "Please wait"
                }
            val prefix =
                if (request.controls().scroll && request.phase() == DialogueView.HintPhase.CHOOSE) {
                    if (request.controlIcons()) "${ControlGlyphs.SCROLL} · "
                    else if (de) "Scrollen · " else "Scroll · "
                } else ""
            val unavailable = if (de) "Nicht verfügbar" else "Unavailable"
            val notice =
                when (request.notice()) {
                    "CHOICES_CHANGED" -> if (de) "Antworten geändert" else "Choices changed"
                    "CHOICE_UNAVAILABLE" -> unavailable
                    else -> ""
                }
            UiLabels(
                "$prefix$action · $verb",
                notice,
                request.unavailableReasons().keys.associateWith { unavailable },
            )
        }
    }
}
