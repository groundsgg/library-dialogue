package gg.grounds.dialogue.core.runtime;

import gg.grounds.dialogue.core.port.DialogueScheduler;
import java.time.Duration;
import java.util.Objects;

public record RuntimeOptions(
    DialogueScheduler scheduler, Budgets budgets, int startRegisterLimit, int inputQueueLimit) {
  public RuntimeOptions {
    Objects.requireNonNull(scheduler);
    Objects.requireNonNull(budgets);
    if (startRegisterLimit < 1 || inputQueueLimit < 1)
      throw new IllegalArgumentException("Positive capacity required");
  }

  public record Budgets(Duration text, Duration prepare, Duration action, Duration cleanup) {
    public Budgets {
      for (var d : new Duration[] {text, prepare, action, cleanup})
        if (d == null || d.isNegative() || d.isZero())
          throw new IllegalArgumentException("Positive finite duration required");
      for (var d : new Duration[] {text, prepare, action, cleanup}) d.toNanos();
    }
  }

  public static RuntimeOptions defaults(DialogueScheduler s) {
    return new RuntimeOptions(
        s,
        new Budgets(
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(30)),
        65536,
        256);
  }
}
