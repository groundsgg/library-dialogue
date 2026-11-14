package gg.grounds.dialogue.minestom;

import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.minestom.render.PresentationStatus;
import java.util.concurrent.CompletionStage;

/** Pending starts can be cancelled without waiting for a public runtime handle. */
public interface MinestomDialogueStart {
  CompletionStage<DialogueStartResult> result();

  CompletionStage<Void> cancel(DialogueEndReason reason);

  PresentationStatus status();
}
