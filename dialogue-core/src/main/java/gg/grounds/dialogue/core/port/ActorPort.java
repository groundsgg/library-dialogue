package gg.grounds.dialogue.core.port;
import java.util.*;
import java.util.concurrent.CompletionStage;
import gg.grounds.dialogue.core.runtime.CancellationToken;
@FunctionalInterface public interface ActorPort {
 record ActorRequest(UUID sessionId,String audienceId,long hostGeneration,Set<String> speakerIds,CancellationToken cancellation,long deadlineNanos){public ActorRequest {speakerIds=Set.copyOf(speakerIds);}}
 record ActorCue(String speaker,String gesture,long nodeVisitId,String cueId) {}
 interface ActorHandle extends OwnedResource {CompletionStage<Void> cue(ActorCue cue);}
 CompletionStage<ActorHandle> prepare(ActorRequest request);
}
