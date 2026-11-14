package gg.grounds.dialogue.core.runtime;
import java.time.Duration;
import java.util.*;
public record PresentationPreferences(String surfaceKey,Duration graphemeDelay,boolean reducedMotion,Map<String,String> effects) {
 public PresentationPreferences {Objects.requireNonNull(surfaceKey);Objects.requireNonNull(graphemeDelay);if(graphemeDelay.isNegative()||graphemeDelay.compareTo(Duration.ofSeconds(60))>0)throw new IllegalArgumentException("Invalid reveal delay");effects=Map.copyOf(effects);}
 public static PresentationPreferences defaults(){return new PresentationPreferences("hud",Duration.ofMillis(35),false,Map.of());}
}
