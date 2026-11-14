package gg.grounds.dialogue.core.runtime;
import java.util.concurrent.CompletionStage;
import gg.grounds.dialogue.core.text.LocaleContext;
public interface DialogueHandle {
 CompletionStage<InputResult> submit(DialogueInput input);
 CompletionStage<RefreshResult> refreshLocale(LocaleContext locale);
 CompletionStage<Void> updatePreferences(PresentationPreferences preferences);
 DialogueView snapshot();
 CompletionStage<DialogueEnd> stop(DialogueEndReason reason);
 CompletionStage<DialogueEnd> completion();
}
