package gg.grounds.dialogue.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CliArgumentsTest {
  @Test
  void validatesCompleteCommandInsteadOfIgnoringTrailingArguments() {
    assertFalse(CliArguments.parse(new String[] {"--help", "junk"}).problems().isEmpty());
    assertFalse(
        CliArguments.parse(new String[] {"validate", "--root", "a", "--root", "b"})
            .problems()
            .isEmpty());
    assertTrue(
        CliArguments.parse(new String[] {"validate", "--root", "a space", "--format", "json"})
            .problems()
            .isEmpty());
  }
}
