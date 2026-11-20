package gg.grounds.dialogue.cli

import gg.grounds.dialogue.core.validation.ValidationReport.Problem
import gg.grounds.dialogue.core.validation.ValidationReport.Severity
import java.nio.file.InvalidPathException
import java.nio.file.Path

@JvmRecord
internal data class CliArguments(val command: Command, val root: Path?, val format: Format) {
    enum class Command {
        VALIDATE,
        HELP,
        VERSION,
    }

    enum class Format {
        TEXT,
        JSON,
    }

    class ParseResult(
        private val arguments: CliArguments?,
        private val outputFormat: Format,
        problems: List<Problem>,
    ) {
        private val problems = java.util.List.copyOf(problems)

        fun arguments(): CliArguments? = arguments

        fun outputFormat(): Format = outputFormat

        fun problems(): List<Problem> = problems
    }

    companion object {
        @JvmStatic
        fun parse(args: Array<String>): ParseResult {
            val formats = args.indices.filter { args[it] == "--format" }
            val format =
                when {
                    formats.isEmpty() -> Format.TEXT
                    formats.size != 1 -> return error(Format.TEXT)
                    else ->
                        when (args.getOrNull(formats.single() + 1)) {
                            "json" -> Format.JSON
                            "text" -> Format.TEXT
                            else -> return error(Format.TEXT)
                        }
                }

            when (args.singleOrNull()) {
                "--help" ->
                    return ParseResult(
                        CliArguments(Command.HELP, null, format),
                        format,
                        emptyList(),
                    )
                "--version" ->
                    return ParseResult(
                        CliArguments(Command.VERSION, null, format),
                        format,
                        emptyList(),
                    )
            }
            if (args.firstOrNull() != "validate") return error(format)

            var root: Path? = null
            var index = 1
            try {
                while (index < args.size) {
                    when (args[index++]) {
                        "--root" -> {
                            val value = args.getOrNull(index++) ?: return error(format)
                            if (root != null || value.startsWith("--")) return error(format)
                            root = Path.of(value)
                        }
                        "--format" -> if (index++ == args.size) return error(format)
                        else -> return error(format)
                    }
                }
            } catch (_: InvalidPathException) {
                return error(format)
            }
            return if (root == null) error(format)
            else ParseResult(CliArguments(Command.VALIDATE, root, format), format, emptyList())
        }

        private fun error(format: Format) =
            ParseResult(
                null,
                format,
                listOf(
                    Problem(
                        "CLI_USAGE",
                        Severity.ERROR,
                        null,
                        null,
                        null,
                        "Invalid command arguments. Use --help.",
                    )
                ),
            )
    }
}
