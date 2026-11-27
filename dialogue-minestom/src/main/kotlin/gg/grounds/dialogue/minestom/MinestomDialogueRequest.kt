package gg.grounds.dialogue.minestom

import gg.grounds.dialogue.core.definition.ArgumentValue
import gg.grounds.dialogue.core.port.ActorPort
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.minestom.input.InputProfile
import net.minestom.server.entity.Player

@JvmSuppressWildcards
class MinestomDialogueRequest
@JvmOverloads
constructor(
    @get:JvmName("requestId") val requestId: String,
    @get:JvmName("player") val player: Player,
    @get:JvmName("hostGeneration") val hostGeneration: Long,
    @get:JvmName("content") val content: ValidatedDialogue,
    @get:JvmName("services") val services: DialogueServices,
    context: Map<String, ArgumentValue>,
    @get:JvmName("locale") val locale: LocaleContext,
    @get:JvmName("preferences") val preferences: PresentationPreferences,
    speakers: Map<String, MinestomSpeakerBinding>,
    @get:JvmName("actors") val actors: ActorPort?,
    @get:JvmName("controls") val controls: InputProfile,
    @get:JvmName("openingImpulse") val openingImpulse: Long,
    @get:JvmName("speechListener")
    val speechListener: MinestomDialogueSpeechListener = MinestomDialogueSpeechListener.NO_OP,
) {
    @get:JvmName("context") val context: Map<String, ArgumentValue> = java.util.Map.copyOf(context)

    @get:JvmName("speakers")
    val speakers: Map<String, MinestomSpeakerBinding> = java.util.Map.copyOf(speakers)

    init {
        require(requestId.isNotBlank() && hostGeneration >= 0 && openingImpulse >= 0) {
            "Invalid start identity"
        }
    }

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
