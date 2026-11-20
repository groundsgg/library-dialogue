package gg.grounds.dialogue.minestom

import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.runtime.RefreshResult
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.minestom.render.PresentationStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Pending starts can be cancelled without waiting for a public runtime handle. */
interface MinestomDialogueStart {
    fun result(): CompletionStage<DialogueStartResult>

    fun cancel(reason: DialogueEndReason): CompletionStage<Void>

    fun status(): PresentationStatus

    /** Retains the latest intent while preparation is still pending. */
    fun updatePreferences(preferences: PresentationPreferences): CompletionStage<Void> =
        CompletableFuture.failedFuture(
            UnsupportedOperationException("Pending preferences unsupported")
        )

    fun refreshLocale(locale: LocaleContext): CompletionStage<RefreshResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Pending locale unsupported"))
}
