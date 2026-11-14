package gg.grounds.dialogue.core.port;
import java.util.concurrent.CompletionStage;
@FunctionalInterface public interface OwnedResource { CompletionStage<Void> closeAsync(); }
