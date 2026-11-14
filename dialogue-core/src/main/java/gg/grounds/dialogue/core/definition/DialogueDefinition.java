package gg.grounds.dialogue.core.definition;
import java.util.*;
/** Immutable author model shared by builders and the JSON codec. Not yet activation proof. */
public record DialogueDefinition(String id, String contentVersion, String entry, LocaleSpec locales,
 Capabilities capabilities, Interaction interaction, Presentation presentation, Map<String,Node> nodes) {
 public DialogueDefinition { Objects.requireNonNull(id); Objects.requireNonNull(contentVersion); Objects.requireNonNull(entry); Objects.requireNonNull(locales); Objects.requireNonNull(capabilities); Objects.requireNonNull(interaction); Objects.requireNonNull(presentation); nodes=Collections.unmodifiableMap(new LinkedHashMap<>(nodes)); }
 public record LocaleSpec(String defaultTag, List<String> supported) { public LocaleSpec { Objects.requireNonNull(defaultTag); supported=List.copyOf(supported); } }
 public record Capabilities(Set<String> required, Set<String> optional) { public Capabilities { required=Set.copyOf(required); optional=Set.copyOf(optional); } }
 public record LookAway(boolean enabled, double exitAngleDegrees, long exitDelayMs, double resetAngleDegrees) {}
 public record Interaction(LookAway lookAway, double maxDistanceBlocks) { public Interaction { Objects.requireNonNull(lookAway); } }
 public record Presentation(String focus, String nearFraming, String vignette, Set<String> hideHud) { public Presentation { Objects.requireNonNull(focus); Objects.requireNonNull(nearFraming); Objects.requireNonNull(vignette); hideHud=Set.copyOf(hideHud); } }
 public sealed interface Node permits Message, Branch, Action, Outcome, End {}
 public record Message(String speaker, String text, String gesture, List<Choice> choices, String next) implements Node {
  public Message { Objects.requireNonNull(speaker); Objects.requireNonNull(text); choices=List.copyOf(choices); if(choices.isEmpty()==(next==null)) throw new IllegalArgumentException("MESSAGE_CONTINUATION"); }
 }
 public record Choice(String id, String text, String to, boolean defaultChoice, Condition condition) { public Choice { Objects.requireNonNull(id); Objects.requireNonNull(text); Objects.requireNonNull(to); } }
 public record Condition(String key, Map<String,ArgumentValue> arguments, boolean negated) { public Condition { Objects.requireNonNull(key); arguments=Map.copyOf(arguments); } }
 public record Case(Condition condition, String to) { public Case { Objects.requireNonNull(condition); Objects.requireNonNull(to); } }
 public record Branch(List<Case> cases, String defaultTo, String unavailableTo) implements Node { public Branch { cases=List.copyOf(cases); Objects.requireNonNull(defaultTo); Objects.requireNonNull(unavailableTo); } }
 public record Action(String key, Map<String,ArgumentValue> arguments, String onSuccess, String onRejected, String onUnavailable, String onUncertain) implements Node { public Action { Objects.requireNonNull(key); arguments=Map.copyOf(arguments); Objects.requireNonNull(onSuccess); Objects.requireNonNull(onRejected); Objects.requireNonNull(onUnavailable); Objects.requireNonNull(onUncertain); } }
 public enum Delivery { LOCAL, DURABLE }
 public record Outcome(String milestone, Delivery delivery, String onAcknowledged, String onUnavailable, String onUncertain) implements Node { public Outcome { Objects.requireNonNull(milestone); Objects.requireNonNull(delivery); Objects.requireNonNull(onAcknowledged); Objects.requireNonNull(onUnavailable); Objects.requireNonNull(onUncertain); } }
 public record End(String reason) implements Node { public End { Objects.requireNonNull(reason); } }
}
