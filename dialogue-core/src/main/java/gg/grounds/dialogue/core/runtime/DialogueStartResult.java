package gg.grounds.dialogue.core.runtime;

import gg.grounds.dialogue.core.validation.ValidationReport.Problem;
import java.util.*;

public sealed interface DialogueStartResult {
  record Started(DialogueHandle handle) implements DialogueStartResult {}

  record Rejected(String code, List<Problem> problems) implements DialogueStartResult {
    public Rejected {
      problems = List.copyOf(problems);
    }
  }
}
