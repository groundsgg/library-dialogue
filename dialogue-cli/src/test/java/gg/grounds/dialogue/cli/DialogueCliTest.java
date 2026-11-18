package gg.grounds.dialogue.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import gg.grounds.dialogue.json.DialogueCatalogLoader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DialogueCliTest {
  @TempDir Path root;
  static final Path EXAMPLE = Path.of("../examples/headless-dialogue/src/main/resources");

  record Run(int code, byte[] out, byte[] err) {
    String stdout() {
      return new String(out, StandardCharsets.UTF_8);
    }

    String stderr() {
      return new String(err, StandardCharsets.UTF_8);
    }

    JsonObject json() {
      return JsonParser.parseString(stdout()).getAsJsonObject();
    }
  }

  static Run run(String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int code = DialogueCli.run(args, out, err);
    return new Run(code, out.toByteArray(), err.toByteArray());
  }

  void project() throws Exception {
    try (var files = Files.walk(EXAMPLE)) {
      for (var p : files.toList()) {
        Path dest = root.resolve(EXAMPLE.relativize(p));
        if (Files.isDirectory(p)) Files.createDirectories(dest);
        else Files.copy(p, dest);
      }
    }
  }

  Run validate() {
    return run("validate", "--root", root.toString(), "--format", "json");
  }

  @Test
  void validProjectHasSamePinAsPublicLoader() throws Exception {
    project();
    var r = validate();
    assertEquals(0, r.code(), r.stdout());
    var json = r.json();
    assertEquals("valid", json.get("status").getAsString());
    assertEquals(1, json.get("reportVersion").getAsInt());
    assertEquals(1, json.get("formatVersion").getAsInt());
    var source = ProjectInputs.read(root).sources();
    var loaded = new DialogueCatalogLoader().reload(source);
    assertEquals(loaded.candidate().contentPin().sha256(), json.get("contentPin").getAsString());
    assertArrayEquals(r.out(), validate().out());
    assertEquals("", r.stderr());
  }

  @Test
  void malformedJsonAndMissingKeysReturnOne() throws Exception {
    project();
    Files.writeString(root.resolve("dialogues/mechanic.json"), "{");
    assertEquals(1, validate().code());
    Files.writeString(root.resolve("dialogues/mechanic.json"), "{}");
    assertEquals(1, validate().code());
  }

  @Test
  void usageOrIoReturnsTwoWithoutPathsOrStacktrace() {
    var r = validate();
    assertEquals(2, r.code());
    assertEquals("error", r.json().get("status").getAsString());
    assertTrue(r.json().get("contentPin").isJsonNull());
    assertFalse(r.stdout().contains(root.toString()));
    assertFalse(r.stdout().contains("Exception"));
    assertEquals(2, run("unknown").code());
    assertEquals(0, run("--help").code());
    assertEquals(0, run("--version").code());
  }

  @Test
  void jsonModeSurvivesUnknownArgumentBeforeOrAfter() {
    for (String[] args :
        new String[][] {{"--wat", "--format", "json"}, {"--format", "json", "--wat"}}) {
      var r = run(args);
      assertEquals(2, r.code());
      assertEquals("error", r.json().get("status").getAsString());
      assertEquals("", r.stderr());
    }
    var contradictory = run("--format", "json", "--format", "text");
    assertEquals(2, contradictory.code());
    assertEquals("", contradictory.stdout());
    assertFalse(contradictory.stderr().isEmpty());
    assertEquals("", run("--format", "yaml").stdout());
  }

  @Test
  void emptyDefinitionsAreContentError() throws Exception {
    project();
    Files.delete(root.resolve("dialogues/mechanic.json"));
    var r = validate();
    assertEquals(1, r.code());
    assertEquals(
        "CATALOGUE_LIMIT",
        r.json().getAsJsonArray("problems").get(0).getAsJsonObject().get("code").getAsString());
  }

  @Test
  void outputFailureNeverReportsSuccess() throws Exception {
    project();
    var out =
        new OutputStream() {
          public void write(int b) throws IOException {
            throw new IOException("broken");
          }
        };
    assertEquals(
        2,
        DialogueCli.run(
            new String[] {"validate", "--root", root.toString(), "--format", "json"},
            out,
            new ByteArrayOutputStream()));
  }

  @Test
  void warningsStayValid() throws Exception {
    project();
    Path definition = root.resolve("dialogues/mechanic.json");
    var json = JsonParser.parseString(Files.readString(definition)).getAsJsonObject();
    var node = new JsonObject();
    node.addProperty("type", "end");
    node.addProperty("reason", "completed");
    json.getAsJsonObject("nodes").add("unused", node);
    Files.writeString(definition, json.toString());
    var result = validate();
    assertEquals(0, result.code(), result.stdout());
    assertTrue(result.stdout().contains("NODE_UNREACHABLE"));
  }

  @Test
  void realChildProcessHasExactExitAndBuildVersion() throws Exception {
    project();
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String cp = System.getProperty("cli.runtimeClasspath");
    var good =
        new ProcessBuilder(
                java,
                "-cp",
                cp,
                DialogueCli.class.getName(),
                "validate",
                "--root",
                root.toString(),
                "--format",
                "json")
            .start();
    String output = new String(good.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, good.waitFor());
    assertEquals(
        "valid", JsonParser.parseString(output).getAsJsonObject().get("status").getAsString());
    Files.writeString(root.resolve("dialogues/mechanic.json"), "{");
    var bad =
        new ProcessBuilder(
                java,
                "-cp",
                cp,
                DialogueCli.class.getName(),
                "validate",
                "--root",
                root.toString(),
                "--format",
                "json")
            .start();
    bad.getInputStream().readAllBytes();
    assertEquals(1, bad.waitFor());
    var version =
        new ProcessBuilder(java, "-cp", cp, DialogueCli.class.getName(), "--version").start();
    assertEquals(
        VersionInfo.current().toolVersion(),
        new String(version.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
    assertEquals(0, version.waitFor());
  }
}
