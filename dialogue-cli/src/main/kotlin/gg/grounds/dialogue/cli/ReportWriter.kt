package gg.grounds.dialogue.cli

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.OutputStream
import java.util.Locale

internal object ReportWriter {
    @JvmStatic
    fun write(report: CliReport, format: CliArguments.Format, output: OutputStream) {
        val status = report.status().name.lowercase(Locale.ROOT)
        val text =
            if (format == CliArguments.Format.JSON) {
                val json =
                    JsonObject().apply {
                        addProperty("reportVersion", report.reportVersion)
                        addProperty("toolVersion", report.toolVersion)
                        addProperty("validatorVersion", report.validatorVersion)
                        addProperty("formatVersion", report.formatVersion)
                        addProperty("status", status)
                        addProperty("contentPin", report.contentPin)
                        add(
                            "problems",
                            JsonArray().apply {
                                report.problems().forEach { problem ->
                                    add(
                                        JsonObject().apply {
                                            addProperty("code", problem.code)
                                            addProperty("severity", problem.severity.name)
                                            addProperty("file", problem.file)
                                            addProperty("path", problem.path)
                                            addProperty("nodeId", problem.nodeId)
                                            addProperty("message", problem.message)
                                        }
                                    )
                                }
                            },
                        )
                    }
                GsonBuilder().serializeNulls().disableHtmlEscaping().create().toJson(json) + "\n"
            } else {
                buildString {
                    append(status)
                    report.contentPin?.let { append(" contentPin=").append(it) }
                    append('\n')
                    report.problems().forEach { problem ->
                        append(problem.severity).append(' ').append(problem.code)
                        problem.file?.let { append(' ').append(it) }
                        problem.path?.let { append(' ').append(it) }
                        append(": ").append(problem.message).append('\n')
                    }
                }
            }
        output.write(text.toByteArray())
        output.flush()
    }
}
