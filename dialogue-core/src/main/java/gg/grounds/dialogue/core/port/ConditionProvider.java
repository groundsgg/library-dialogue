package gg.grounds.dialogue.core.port;
import java.util.*;
import java.util.concurrent.CompletionStage;
import gg.grounds.dialogue.core.definition.ArgumentValue;
@FunctionalInterface public interface ConditionProvider {
 record ConditionRequest(String key,Map<String,ArgumentValue> arguments,String audienceId,long hostGeneration,UUID sessionId,long nodeVisitId){public ConditionRequest {arguments=Map.copyOf(arguments);}}
 enum ConditionResult { TRUE,FALSE,UNAVAILABLE }
 CompletionStage<ConditionResult> evaluate(ConditionRequest request);
}
