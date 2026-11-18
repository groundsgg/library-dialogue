package gg.grounds.dialogue.cli;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.validation.ValidationReport.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class CliValidationTest {
  @Test
  void reportsPreserveWarningsUnicodeAndStableNullFirstOrder() throws Exception {
    var warning = new Problem("W", Severity.WARNING, "texts/de.json", "/ü", null, "Grüße");
    var global = new Problem("A", Severity.WARNING, null, null, null, "global");
    var report =
        new CliReport(
            1, "test", "test", 1, CliReport.Status.VALID, "pin", List.of(warning, global));
    assertEquals(0, report.exitCode());
    var out = new ByteArrayOutputStream();
    ReportWriter.write(report, CliArguments.Format.JSON, out);
    String json = out.toString(StandardCharsets.UTF_8);
    assertTrue(json.indexOf("global") < json.indexOf("Grüße"));
    assertTrue(json.contains("Grüße"));
    assertTrue(json.contains("\"nodeId\":null"));
  }
}
