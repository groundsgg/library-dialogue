package gg.grounds.dialogue.json;
import gg.grounds.dialogue.core.definition.DialogueDefinition;
import gg.grounds.dialogue.core.text.TextCatalog;
import gg.grounds.dialogue.core.validation.*;
public final class ContentCanonicalizer { public ContentPin pin(DialogueDefinition d,TextCatalog t) { return ContentFingerprint.pin(d,t); } }
