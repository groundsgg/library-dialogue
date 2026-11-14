package gg.grounds.dialogue.core.port;
import java.util.*;
import java.util.concurrent.CompletionStage;
import gg.grounds.dialogue.core.runtime.*;
@FunctionalInterface public interface DialogueRenderer {
 /** A rejected reconfiguration has left the previous presentation intact. */
 class ReconfigurationRejected extends DialogueFailure {public ReconfigurationRejected(String code,Throwable cause){super(code,cause);}}
 record PresentationRequest(UUID sessionId,String audienceId,long hostGeneration,PresentationPreferences preferences,CancellationToken cancellation,long deadlineNanos) {}
 interface PresentationHandle extends OwnedResource {CompletionStage<Void> replace(DialogueView view);CompletionStage<Void> reconfigure(PresentationPreferences preferences);}
 CompletionStage<PresentationHandle> prepare(PresentationRequest request);
}
