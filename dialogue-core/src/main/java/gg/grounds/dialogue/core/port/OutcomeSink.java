package gg.grounds.dialogue.core.port;
import java.util.*;
import java.util.concurrent.CompletionStage;
import gg.grounds.dialogue.core.definition.DialogueDefinition.Delivery;
@FunctionalInterface public interface OutcomeSink {
 record OutcomeEvent(UUID eventId,String milestone,Delivery delivery,String audienceId,long hostGeneration,UUID sessionId,long nodeVisitId,String payloadHash) {}
 enum OutcomeResult { ACKNOWLEDGED,UNAVAILABLE,UNCERTAIN }
 CompletionStage<OutcomeResult> deliver(OutcomeEvent event);
}
