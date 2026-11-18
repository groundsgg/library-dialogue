package gg.grounds.dialogue.cli;

import gg.grounds.dialogue.core.validation.ValidationReport.*;
import java.nio.file.*;
import java.util.*;

record CliArguments(Command command, Path root, Format format) {
  enum Command {
    VALIDATE,
    HELP,
    VERSION
  }

  enum Format {
    TEXT,
    JSON
  }

  record ParseResult(CliArguments arguments, Format outputFormat, List<Problem> problems) {
    ParseResult {
      problems = List.copyOf(problems);
    }
  }

  static ParseResult parse(String[] args) {
    Format format = Format.TEXT;
    int formats = 0;
    boolean badFormat = false;
    for (int i = 0; i < args.length; i++)
      if (args[i].equals("--format")) {
        formats++;
        if (i + 1 >= args.length) badFormat = true;
        else if (args[i + 1].equals("json")) format = Format.JSON;
        else if (!args[i + 1].equals("text")) badFormat = true;
      }
    if (badFormat || formats > 1) return error(Format.TEXT);
    if (args.length == 1 && args[0].equals("--help"))
      return new ParseResult(new CliArguments(Command.HELP, null, format), format, List.of());
    if (args.length == 1 && args[0].equals("--version"))
      return new ParseResult(new CliArguments(Command.VERSION, null, format), format, List.of());
    if (args.length == 0 || !args[0].equals("validate")) return error(format);
    Path root = null;
    try {
      for (int i = 1; i < args.length; i++)
        switch (args[i]) {
          case "--root" -> {
            if (root != null || i + 1 >= args.length || args[i + 1].startsWith("--"))
              return error(format);
            root = Path.of(args[++i]);
          }
          case "--format" -> {
            if (++i >= args.length) return error(format);
          }
          default -> {
            return error(format);
          }
        }
    } catch (InvalidPathException e) {
      return error(format);
    }
    if (root == null) return error(format);
    return new ParseResult(new CliArguments(Command.VALIDATE, root, format), format, List.of());
  }

  private static ParseResult error(Format format) {
    return new ParseResult(
        null,
        format,
        List.of(
            new Problem(
                "CLI_USAGE",
                Severity.ERROR,
                null,
                null,
                null,
                "Invalid command arguments. Use --help.")));
  }
}
