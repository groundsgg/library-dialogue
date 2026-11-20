package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.internal.SessionDriver
import gg.grounds.dialogue.core.validation.ContentFingerprint
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Local runtime. Network authority and durable job registries belong to the host. */
class DialogueRuntime private constructor(private val options: RuntimeOptions) {
    private val lock = Any()
    private val starts = HashMap<String, Entry>()
    private var closedView: CompletionStage<Void>? = null

    private class Entry(
        val identity: String,
        val driver: SessionDriver,
        val observation: CompletionStage<DialogueStartResult>,
    )

    fun start(request: DialogueStart): CompletionStage<DialogueStartResult> {
        val identity = identity(request)
        val entry =
            synchronized(lock) {
                if (closedView != null) return reject("HOST_CLOSED")
                val existing = starts[request.requestId()]
                if (existing != null) {
                    if (existing.identity == identity) return existing.observation
                    return reject("START_REQUEST_CONFLICT")
                }
                if (starts.size >= options.startRegisterLimit) return reject("START_REGISTER_FULL")
                val result = CompletableFuture<DialogueStartResult>()
                Entry(
                        identity,
                        SessionDriver(request, options, result),
                        result.minimalCompletionStage(),
                    )
                    .also { starts[request.requestId()] = it }
            }
        entry.driver.begin()
        return entry.observation
    }

    fun closeAsync(): CompletionStage<Void> {
        val result = CompletableFuture<Void>()
        val view = result.minimalCompletionStage()
        val entries =
            synchronized(lock) {
                closedView?.let {
                    return it
                }
                closedView = view
                starts.values.toList()
            }
        val futures = entries.map {
            it.driver.stop(DialogueEndReason.HOST_CLOSED).toCompletableFuture()
        }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, error ->
            synchronized(lock) { starts.clear() }
            if (error == null) result.complete(null) else result.completeExceptionally(error)
        }
        return view
    }

    private fun reject(code: String): CompletionStage<DialogueStartResult> =
        CompletableFuture.completedFuture(DialogueStartResult.Rejected(code, emptyList()))

    companion object {
        @JvmStatic fun create(options: RuntimeOptions): DialogueRuntime = DialogueRuntime(options)

        private fun identity(request: DialogueStart): String {
            val locale = request.locale()
            val preferences = request.preferences()
            val values =
                mapOf(
                    "audience" to request.audienceId(),
                    "hostGeneration" to request.hostGeneration(),
                    "content" to request.content().contentPin().sha256,
                    "bindings" to request.bindings().generation(),
                    "authorGeneration" to request.content().catalog().generation(),
                    "validationPolicy" to request.content().limits().toString(),
                    "locale" to
                        mapOf(
                            "tag" to locale.tag(),
                            "revision" to locale.revision(),
                            "fallback" to locale.fallbackTag(),
                        ),
                    "preferences" to
                        mapOf(
                            "surface" to preferences.surfaceKey(),
                            "revealNanos" to preferences.graphemeDelay().toNanos(),
                            "reducedMotion" to preferences.reducedMotion(),
                            "effects" to preferences.effects(),
                        ),
                    "context" to ContentFingerprint.argumentData(request.context()),
                    "cancellation" to request.cancellation().id().toString(),
                    "openingImpulse" to request.openingImpulse(),
                )
            return checkNotNull(
                ContentFingerprint.hash(ContentFingerprint.canonicalBytes(values)).sha256
            )
        }
    }
}
