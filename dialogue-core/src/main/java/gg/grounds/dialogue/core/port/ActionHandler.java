package gg.grounds.dialogue.core.port;
import java.util.*;
import java.util.concurrent.CompletionStage;
import gg.grounds.dialogue.core.definition.ArgumentValue;
/** Durable handlers register/deduplicate before dispatch; the core never supplies a database. */
public interface ActionHandler {
 record ActionRef(UUID requestId,String payloadHash,String audienceId,long hostGeneration,UUID sessionId,long nodeVisitId) {}
 record ActionRequest(ActionRef reference,String key,Map<String,ArgumentValue> arguments,boolean durable){public ActionRequest {arguments=Map.copyOf(arguments);}}
 enum ActionResult { SUCCESS,REJECTED,UNAVAILABLE,UNCERTAIN }
 enum ActionStatus { PENDING,SUCCEEDED,REJECTED,UNAVAILABLE }
 CompletionStage<ActionResult> execute(ActionRequest request);
 CompletionStage<ActionStatus> reconcile(ActionRef reference);
}
