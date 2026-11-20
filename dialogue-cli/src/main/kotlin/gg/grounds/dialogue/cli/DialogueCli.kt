package gg.grounds.dialogue.cli

import gg.grounds.dialogue.core.validation.ValidationReport.Problem
import gg.grounds.dialogue.core.validation.ValidationReport.Severity
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.system.exitProcess

/** Read-only local author-project validator. */
class DialogueCli private constructor() {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            exitProcess(
                run(
                    args,
                    FileOutputStream(FileDescriptor.out),
                    FileOutputStream(FileDescriptor.err),
                )
            )
        }

        internal fun run(args: Array<String>, stdout: OutputStream, stderr: OutputStream): Int {
            val parsed = CliArguments.parse(args)
            val version =
                try {
                    VersionInfo.current()
                } catch (_: RuntimeException) {
                    return 2
                }

            val report =
                try {
                    if (parsed.problems().isNotEmpty()) {
                        CliReport.error(version, parsed.problems())
                    } else {
                        val command = checkNotNull(parsed.arguments())
                        if (command.command != CliArguments.Command.VALIDATE) {
                            val text =
                                if (command.command == CliArguments.Command.VERSION) {
                                    version.toolVersion + "\n"
                                } else {
                                    "dialogue validate --root <directory> [--format text|json]\n" +
                                        "dialogue --help\ndialogue --version\n"
                                }
                            stdout.write(text.toByteArray())
                            stdout.flush()
                            return 0
                        }
                        val inputs = ProjectInputs.read(checkNotNull(command.root))
                        val sources = inputs.sources()
                        if (sources == null) CliReport.error(version, inputs.problems())
                        else CliValidation.validate(sources, version)
                    }
                } catch (_: IOException) {
                    return 2
                } catch (_: RuntimeException) {
                    CliReport.error(
                        version,
                        listOf(
                            Problem(
                                "CLI_INTERNAL",
                                Severity.ERROR,
                                null,
                                null,
                                null,
                                "Validation could not be completed",
                            )
                        ),
                    )
                }

            val format = parsed.outputFormat()
            val output =
                if (format == CliArguments.Format.TEXT && report.status() == CliReport.Status.ERROR)
                    stderr
                else stdout
            return try {
                ReportWriter.write(report, format, output)
                report.exitCode()
            } catch (_: IOException) {
                2
            }
        }
    }
}
