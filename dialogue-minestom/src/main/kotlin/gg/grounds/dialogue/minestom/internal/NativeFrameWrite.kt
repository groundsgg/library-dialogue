package gg.grounds.dialogue.minestom.internal

import gg.grounds.dialogue.presentation.DialogueSurface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.function.BooleanSupplier
import java.util.function.Supplier

/** Only the enclosing operation's own validation can certify its precommit state. */
internal fun nativeFrameWrite(
    provenPrecommit: BooleanSupplier = BooleanSupplier { false },
    operation: Supplier<CompletionStage<Void>>,
): CompletionStage<Void> {
    val result = CompletableFuture<Void>()
    fun failed(error: Throwable) {
        var cause = error
        while (cause is CompletionException || cause is ExecutionException) {
            cause = cause.cause ?: break
        }
        val failure =
            if (!provenPrecommit.asBoolean && cause is DialogueSurface.FrameRejected)
                IllegalStateException(
                    "Native frame failed without an enclosing precommit guarantee",
                    error,
                )
            else error
        result.completeExceptionally(failure)
    }
    try {
        operation.get().whenComplete { _, error ->
            if (error == null) result.complete(null) else failed(error)
        }
    } catch (error: Throwable) {
        failed(error)
    }
    return result.minimalCompletionStage()
}
