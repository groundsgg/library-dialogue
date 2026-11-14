package gg.grounds.dialogue.core.definition;

import java.math.BigDecimal;
import java.util.Objects;

/** Safe scalar arguments. They never carry commands or executable formatting. */
public sealed interface ArgumentValue {
  record Text(String value) implements ArgumentValue {
    public Text {
      Objects.requireNonNull(value);
    }
  }

  record Number(BigDecimal value) implements ArgumentValue {
    public Number {
      Objects.requireNonNull(value);
      value = value.stripTrailingZeros();
    }
  }

  record Flag(boolean value) implements ArgumentValue {}

  enum Nil implements ArgumentValue {
    INSTANCE
  }

  static ArgumentValue of(String value) {
    return new Text(value);
  }

  static ArgumentValue of(boolean value) {
    return new Flag(value);
  }

  static ArgumentValue of(long value) {
    return new Number(BigDecimal.valueOf(value));
  }
}
