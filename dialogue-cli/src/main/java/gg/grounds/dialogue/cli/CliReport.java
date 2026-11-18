package gg.grounds.dialogue.cli;

import gg.grounds.dialogue.core.validation.ValidationReport.*;
import java.util.*;

record CliReport(
    int reportVersion,
    String toolVersion,
    String validatorVersion,
    int formatVersion,
    Status status,
    String contentPin,
    List<Problem> problems) {
  enum Status {
    VALID,
    INVALID,
    ERROR
  }

  CliReport {
    var text = Comparator.nullsFirst(Comparator.<String>naturalOrder());
    problems =
        problems.stream()
            .sorted(
                Comparator.comparing(Problem::file, text)
                    .thenComparing(Problem::path, text)
                    .thenComparing(Problem::nodeId, text)
                    .thenComparing(p -> p.severity().name(), text)
                    .thenComparing(Problem::code, text)
                    .thenComparing(Problem::message, text))
            .toList();
    if (status != Status.VALID) contentPin = null;
  }

  int exitCode() {
    return switch (status) {
      case VALID -> 0;
      case INVALID -> 1;
      case ERROR -> 2;
    };
  }

  static CliReport error(VersionInfo version, List<Problem> problems) {
    return new CliReport(
        1, version.toolVersion(), version.validatorVersion(), 1, Status.ERROR, null, problems);
  }
}
