package gg.grounds.dialogue.minestom.input;

import java.time.Duration;

/** Quiet-period heuristic, not a claim to detect physical key release. */
public final class ClickBurstGate {
  private final long quietNanos;
  private long last;
  private boolean seen;

  public ClickBurstGate(Duration quiet) {
    quietNanos = quiet.toNanos();
    if (quietNanos <= 0) throw new IllegalArgumentException("quiet");
  }

  public void opened(long now) {
    last = now;
    seen = true;
  }

  public boolean accept(long now) {
    boolean accept = !seen || now - last >= quietNanos;
    last = now;
    seen = true;
    return accept;
  }
}
