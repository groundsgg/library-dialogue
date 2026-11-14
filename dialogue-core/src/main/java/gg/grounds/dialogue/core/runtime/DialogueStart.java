package gg.grounds.dialogue.core.runtime;
import java.util.*;
import gg.grounds.dialogue.core.definition.ArgumentValue;
import gg.grounds.dialogue.core.port.HostBindings;
import gg.grounds.dialogue.core.text.LocaleContext;
import gg.grounds.dialogue.core.validation.ValidatedDialogue;
public record DialogueStart(String requestId,String audienceId,long hostGeneration,ValidatedDialogue content,HostBindings bindings,Map<String,ArgumentValue> context,LocaleContext locale,PresentationPreferences preferences,CancellationToken cancellation,long openingImpulse) {
 public DialogueStart {Objects.requireNonNull(requestId);Objects.requireNonNull(audienceId);Objects.requireNonNull(content);Objects.requireNonNull(bindings);context=Map.copyOf(context);Objects.requireNonNull(locale);Objects.requireNonNull(preferences);Objects.requireNonNull(cancellation);if(requestId.isBlank()||hostGeneration<0||openingImpulse<0)throw new IllegalArgumentException("Invalid start identity");}
}
