package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.text.LocaleContext
import java.util.concurrent.CompletionStage

interface DialogueHandle {
    fun submit(input: DialogueInput): CompletionStage<InputResult>

    fun refreshLocale(locale: LocaleContext): CompletionStage<RefreshResult>

    fun updatePreferences(preferences: PresentationPreferences): CompletionStage<Void>

    fun snapshot(): DialogueView

    fun stop(reason: DialogueEndReason): CompletionStage<DialogueEnd>

    fun completion(): CompletionStage<DialogueEnd>
}
