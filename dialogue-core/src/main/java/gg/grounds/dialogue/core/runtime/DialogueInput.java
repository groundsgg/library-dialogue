package gg.grounds.dialogue.core.runtime;
import java.util.UUID;
public sealed interface DialogueInput {
 record InputStamp(UUID sessionId,long renderRevision,long impulseId) {}
 record Select(InputStamp stamp,String choiceId) implements DialogueInput {}
 record Confirm(InputStamp stamp,String choiceId) implements DialogueInput {}
 record RevealFinished(UUID sessionId,long renderRevision) implements DialogueInput {}
 record Cancel(DialogueEndReason reason) implements DialogueInput {}
}
