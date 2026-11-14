package gg.grounds.dialogue.core.validation;
import gg.grounds.dialogue.core.definition.DialogueDefinition;
import gg.grounds.dialogue.core.text.TextCatalog;
/** Only the validator issues activation proof; public callers cannot forge it. */
public final class ValidatedDialogue {
 private final DialogueDefinition definition; private final TextCatalog texts; private final HostCatalog catalog; private final ValidationLimits limits; private final ContentPin contentPin;
 ValidatedDialogue(DialogueDefinition d,TextCatalog t,HostCatalog c,ValidationLimits l) { definition=d;texts=t;catalog=c;limits=l;contentPin=ContentFingerprint.pin(d,t); }
 public ContentPin contentPin() { return contentPin; }
 public DialogueDefinition definition() { return definition; }
 public TextCatalog texts() { return texts; }
 public HostCatalog catalog() { return catalog; }
 public ValidationLimits limits() { return limits; }
}
