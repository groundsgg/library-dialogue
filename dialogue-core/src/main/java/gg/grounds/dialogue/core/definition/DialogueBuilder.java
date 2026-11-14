package gg.grounds.dialogue.core.definition;

import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import java.util.*;

/** Java authoring path. Activation still requires DialogueValidator. */
public final class DialogueBuilder {
  private final String id;
  private String version = "1.0.0", entry = "start";
  private LocaleSpec locales = new LocaleSpec("en", List.of("en"));
  private Capabilities capabilities = new Capabilities(Set.of(), Set.of());
  private Interaction interaction = new Interaction(new LookAway(true, 110, 600, 95), 6);
  private Presentation presentation = new Presentation("none", "none", "none", Set.of());
  private final Map<String, Node> nodes = new LinkedHashMap<>();

  private DialogueBuilder(String id) {
    this.id = Objects.requireNonNull(id);
  }

  public static DialogueBuilder dialogue(String id) {
    return new DialogueBuilder(id);
  }

  public DialogueBuilder version(String value) {
    version = value;
    return this;
  }

  public DialogueBuilder entry(String value) {
    entry = value;
    return this;
  }

  public DialogueBuilder locales(String defaultTag, String... supported) {
    locales = new LocaleSpec(defaultTag, List.of(supported));
    return this;
  }

  public DialogueBuilder capabilities(Capabilities value) {
    capabilities = value;
    return this;
  }

  public DialogueBuilder interaction(Interaction value) {
    interaction = value;
    return this;
  }

  public DialogueBuilder presentation(Presentation value) {
    presentation = value;
    return this;
  }

  public DialogueBuilder node(String id, Node node) {
    if (nodes.putIfAbsent(Objects.requireNonNull(id), Objects.requireNonNull(node)) != null)
      throw new IllegalArgumentException("DUPLICATE_NODE_ID: " + id);
    return this;
  }

  public DialogueDefinition build() {
    return new DialogueDefinition(
        id, version, entry, locales, capabilities, interaction, presentation, nodes);
  }
}
