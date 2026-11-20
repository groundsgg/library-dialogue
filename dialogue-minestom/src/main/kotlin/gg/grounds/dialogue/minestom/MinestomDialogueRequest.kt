package gg.grounds.dialogue.minestom

import gg.grounds.dialogue.core.definition.ArgumentValue
import gg.grounds.dialogue.core.port.ActorPort
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.minestom.input.InputProfile
import net.minestom.server.entity.Player

@JvmSuppressWildcards
class MinestomDialogueRequest(
    private val requestId: String,
    private val player: Player,
    private val hostGeneration: Long,
    private val content: ValidatedDialogue,
    private val services: DialogueServices,
    context: Map<String, ArgumentValue>,
    private val locale: LocaleContext,
    private val preferences: PresentationPreferences,
    speakers: Map<String, MinestomSpeakerBinding>,
    private val actors: ActorPort?,
    private val controls: InputProfile,
    private val openingImpulse: Long,
) {
    private val context = java.util.Map.copyOf(context)
    private val speakers = java.util.Map.copyOf(speakers)

    init {
        require(requestId.isNotBlank() && hostGeneration >= 0 && openingImpulse >= 0) {
            "Invalid start identity"
        }
    }

    fun requestId(): String = requestId

    fun player(): Player = player

    fun hostGeneration(): Long = hostGeneration

    fun content(): ValidatedDialogue = content

    fun services(): DialogueServices = services

    fun context(): Map<String, ArgumentValue> = context

    fun locale(): LocaleContext = locale

    fun preferences(): PresentationPreferences = preferences

    fun speakers(): Map<String, MinestomSpeakerBinding> = speakers

    fun actors(): ActorPort? = actors

    fun controls(): InputProfile = controls

    fun openingImpulse(): Long = openingImpulse

    override fun equals(other: Any?): Boolean =
        other is MinestomDialogueRequest &&
            requestId == other.requestId &&
            player == other.player &&
            hostGeneration == other.hostGeneration &&
            content == other.content &&
            services == other.services &&
            context == other.context &&
            locale == other.locale &&
            preferences == other.preferences &&
            speakers == other.speakers &&
            actors == other.actors &&
            controls == other.controls &&
            openingImpulse == other.openingImpulse

    override fun hashCode(): Int {
        var hash = requestId.hashCode()
        hash = 31 * hash + player.hashCode()
        hash = 31 * hash + hostGeneration.hashCode()
        hash = 31 * hash + content.hashCode()
        hash = 31 * hash + services.hashCode()
        hash = 31 * hash + context.hashCode()
        hash = 31 * hash + locale.hashCode()
        hash = 31 * hash + preferences.hashCode()
        hash = 31 * hash + speakers.hashCode()
        hash = 31 * hash + (actors?.hashCode() ?: 0)
        hash = 31 * hash + controls.hashCode()
        hash = 31 * hash + openingImpulse.hashCode()
        return hash
    }

    override fun toString(): String =
        "MinestomDialogueRequest[requestId=$requestId, player=$player, hostGeneration=$hostGeneration, content=$content, services=$services, context=$context, locale=$locale, preferences=$preferences, speakers=$speakers, actors=$actors, controls=$controls, openingImpulse=$openingImpulse]"
}
