package gg.grounds.dialogue.presentation;
import java.util.*;
import java.util.concurrent.CompletionStage;
import gg.grounds.dialogue.core.port.OwnedResource;
import gg.grounds.dialogue.core.runtime.CancellationToken;
/** An open surface is hidden until its first frame. Close prevents all later mutations. */
@FunctionalInterface public interface DialogueSurface {
 record SurfaceContext(UUID sessionId,String audienceId,long hostGeneration,String surfaceKey,String profilePin,CancellationToken cancellation,long generation,long deadlineNanos) {}
 interface SurfaceHandle extends OwnedResource {CompletionStage<Void> replace(WidgetFrame frame);}
 CompletionStage<SurfaceHandle> open(SurfaceContext context);
}
