package gg.grounds.dialogue.minestom;

import gg.grounds.dialogue.core.definition.ArgumentValue;
import gg.grounds.dialogue.core.port.ActorPort;
import gg.grounds.dialogue.core.runtime.PresentationPreferences;
import gg.grounds.dialogue.core.text.LocaleContext;
import gg.grounds.dialogue.core.validation.ValidatedDialogue;
import gg.grounds.dialogue.minestom.input.InputProfile;
import java.util.*;
import net.minestom.server.entity.Player;

public record MinestomDialogueRequest(
    String requestId,
    Player player,
    long hostGeneration,
    ValidatedDialogue content,
    DialogueServices services,
    Map<String, ArgumentValue> context,
    LocaleContext locale,
    PresentationPreferences preferences,
    Map<String, MinestomSpeakerBinding> speakers,
    ActorPort actors,
    InputProfile controls,
    long openingImpulse) {
  public MinestomDialogueRequest {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(player);
    Objects.requireNonNull(content);
    Objects.requireNonNull(services);
    Objects.requireNonNull(locale);
    Objects.requireNonNull(preferences);
    Objects.requireNonNull(controls);
    context = Map.copyOf(context);
    speakers = Map.copyOf(speakers);
    if (requestId.isBlank() || hostGeneration < 0 || openingImpulse < 0)
      throw new IllegalArgumentException("Invalid start identity");
  }
}
