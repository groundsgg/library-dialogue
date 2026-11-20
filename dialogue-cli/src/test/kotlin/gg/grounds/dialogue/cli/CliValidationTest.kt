package gg.grounds.dialogue.cli

import gg.grounds.dialogue.core.validation.ValidationReport.Problem
import gg.grounds.dialogue.core.validation.ValidationReport.Severity
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class CliValidationTest {
    @Test
    fun reportsPreserveWarningsUnicodeAndStableNullFirstOrder() {
        val warning = Problem("W", Severity.WARNING, "texts/de.json", "/ü", null, "Grüße")
        val global = Problem("A", Severity.WARNING, null, null, null, "global")
        val report =
            CliReport(1, "test", "test", 1, CliReport.Status.VALID, "pin", listOf(warning, global))
        assertEquals(0, report.exitCode())
        val out = ByteArrayOutputStream()
        ReportWriter.write(report, CliArguments.Format.JSON, out)
        val json = out.toString(StandardCharsets.UTF_8)
        assertTrue(json.indexOf("global") < json.indexOf("Grüße"))
        assertTrue(json.contains("Grüße"))
        assertTrue(json.contains("\"nodeId\":null"))
    }
}
