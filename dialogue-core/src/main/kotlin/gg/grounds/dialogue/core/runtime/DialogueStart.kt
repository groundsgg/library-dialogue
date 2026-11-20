package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.definition.ArgumentValue
import gg.grounds.dialogue.core.port.HostBindings
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.validation.ValidatedDialogue

@JvmSuppressWildcards
class DialogueStart(
    private val requestId: String,
    private val audienceId: String,
    private val hostGeneration: Long,
    private val content: ValidatedDialogue,
    private val bindings: HostBindings,
    context: Map<String, ArgumentValue>,
    private val locale: LocaleContext,
    private val preferences: PresentationPreferences,
    private val cancellation: CancellationToken,
    private val openingImpulse: Long,
) {
    private val context = java.util.Map.copyOf(context)

    init {
        require(requestId.isNotBlank() && hostGeneration >= 0 && openingImpulse >= 0) {
            "Invalid start identity"
        }
    }

    fun requestId(): String = requestId

    fun audienceId(): String = audienceId

    fun hostGeneration(): Long = hostGeneration

    fun content(): ValidatedDialogue = content

    fun bindings(): HostBindings = bindings

    fun context(): Map<String, ArgumentValue> = context

    fun locale(): LocaleContext = locale

    fun preferences(): PresentationPreferences = preferences

    fun cancellation(): CancellationToken = cancellation

    fun openingImpulse(): Long = openingImpulse

    override fun equals(other: Any?): Boolean =
        other is DialogueStart &&
            requestId == other.requestId &&
            audienceId == other.audienceId &&
            hostGeneration == other.hostGeneration &&
            content == other.content &&
            bindings == other.bindings &&
            context == other.context &&
            locale == other.locale &&
            preferences == other.preferences &&
            cancellation == other.cancellation &&
            openingImpulse == other.openingImpulse

    override fun hashCode(): Int {
        var hash = requestId.hashCode()
        hash = 31 * hash + audienceId.hashCode()
        hash = 31 * hash + hostGeneration.hashCode()
        hash = 31 * hash + content.hashCode()
        hash = 31 * hash + bindings.hashCode()
        hash = 31 * hash + context.hashCode()
        hash = 31 * hash + locale.hashCode()
        hash = 31 * hash + preferences.hashCode()
        hash = 31 * hash + cancellation.hashCode()
        hash = 31 * hash + openingImpulse.hashCode()
        return hash
    }

    override fun toString(): String =
        "DialogueStart[requestId=$requestId, audienceId=$audienceId, hostGeneration=$hostGeneration, content=$content, bindings=$bindings, context=$context, locale=$locale, preferences=$preferences, cancellation=$cancellation, openingImpulse=$openingImpulse]"
}
