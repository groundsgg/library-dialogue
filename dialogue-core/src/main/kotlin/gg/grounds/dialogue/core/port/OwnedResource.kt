package gg.grounds.dialogue.core.port

import gg.grounds.dialogue.core.runtime.DialogueFailure
import java.util.concurrent.CompletionStage

fun interface OwnedResource {
    fun closeAsync(): CompletionStage<Void>

    /** Preparation failed after resources were acquired and cleanup could not be confirmed. */
    class PreparationFailure(cause: Throwable?) :
        DialogueFailure("PREPARATION_CLEANUP_FAILED", cause)
}
