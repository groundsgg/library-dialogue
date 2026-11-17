package gg.grounds.dialogue.minestom.render;

import java.util.Objects;

public record PresentationStatus(
    String requestedSurface, String effectiveSurface, String diagnostic, long surfaceGeneration) {
  public PresentationStatus {
    Objects.requireNonNull(requestedSurface);
    Objects.requireNonNull(effectiveSurface);
    Objects.requireNonNull(diagnostic);
    if (surfaceGeneration < 0) throw new IllegalArgumentException("generation");
  }
}
