package gg.grounds.dialogue.cli;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class ReportWriter {
  static void write(CliReport report, CliArguments.Format format, OutputStream output)
      throws IOException {
    String text;
    if (format == CliArguments.Format.JSON) {
      var json = new JsonObject();
      json.addProperty("reportVersion", report.reportVersion());
      json.addProperty("toolVersion", report.toolVersion());
      json.addProperty("validatorVersion", report.validatorVersion());
      json.addProperty("formatVersion", report.formatVersion());
      json.addProperty("status", report.status().name().toLowerCase(Locale.ROOT));
      json.addProperty("contentPin", report.contentPin());
      var problems = new JsonArray();
      for (var p : report.problems()) {
        var item = new JsonObject();
        item.addProperty("code", p.code());
        item.addProperty("severity", p.severity().name());
        item.addProperty("file", p.file());
        item.addProperty("path", p.path());
        item.addProperty("nodeId", p.nodeId());
        item.addProperty("message", p.message());
        problems.add(item);
      }
      json.add("problems", problems);
      text = new GsonBuilder().serializeNulls().disableHtmlEscaping().create().toJson(json) + "\n";
    } else {
      var b = new StringBuilder(report.status().name().toLowerCase(Locale.ROOT));
      if (report.contentPin() != null) b.append(" contentPin=").append(report.contentPin());
      b.append('\n');
      for (var p : report.problems()) {
        b.append(p.severity()).append(' ').append(p.code());
        if (p.file() != null) b.append(' ').append(p.file());
        if (p.path() != null) b.append(' ').append(p.path());
        b.append(": ").append(p.message()).append('\n');
      }
      text = b.toString();
    }
    output.write(text.getBytes(StandardCharsets.UTF_8));
    output.flush();
  }
}
