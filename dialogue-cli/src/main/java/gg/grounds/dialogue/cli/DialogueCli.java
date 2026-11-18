package gg.grounds.dialogue.cli;

import gg.grounds.dialogue.core.validation.ValidationReport.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Read-only local author-project validator. */
public final class DialogueCli {
  private DialogueCli() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, OutputStream stdout, OutputStream stderr) {
    var parsed = CliArguments.parse(args);
    VersionInfo version;
    try {
      version = VersionInfo.current();
    } catch (RuntimeException e) {
      return 2;
    }
    CliReport report;
    try {
      if (!parsed.problems().isEmpty()) report = CliReport.error(version, parsed.problems());
      else {
        var command = parsed.arguments();
        if (command.command() != CliArguments.Command.VALIDATE) {
          String text =
              command.command() == CliArguments.Command.VERSION
                  ? version.toolVersion() + "\n"
                  : "dialogue validate --root <directory> [--format text|json]\n"
                      + "dialogue --help\n"
                      + "dialogue --version\n";
          stdout.write(text.getBytes(StandardCharsets.UTF_8));
          stdout.flush();
          return 0;
        }
        var inputs = ProjectInputs.read(command.root());
        report =
            inputs.sources() == null
                ? CliReport.error(version, inputs.problems())
                : CliValidation.validate(inputs.sources(), version);
      }
    } catch (IOException e) {
      return 2;
    } catch (RuntimeException e) {
      report =
          CliReport.error(
              version,
              List.of(
                  new Problem(
                      "CLI_INTERNAL",
                      Severity.ERROR,
                      null,
                      null,
                      null,
                      "Validation could not be completed")));
    }
    try {
      ReportWriter.write(
          report,
          parsed.outputFormat(),
          parsed.outputFormat() == CliArguments.Format.TEXT
                  && report.status() == CliReport.Status.ERROR
              ? stderr
              : stdout);
      return report.exitCode();
    } catch (IOException e) {
      return 2;
    }
  }
}
