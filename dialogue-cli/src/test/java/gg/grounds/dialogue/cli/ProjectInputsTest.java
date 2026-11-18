package gg.grounds.dialogue.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ProjectInputsTest {
  @TempDir Path root;

  void setup() throws Exception {
    Files.createDirectories(root.resolve("dialogues"));
    Files.createDirectories(root.resolve("texts"));
    Files.createDirectories(root.resolve("context"));
    Files.writeString(root.resolve("context/host-catalog.json"), "{}");
    Files.writeString(root.resolve("texts/en.json"), "{}");
  }

  String code(ProjectInputs.Result r) {
    assertNull(r.sources());
    return r.problems().getFirst().code();
  }

  @Test
  void readsEveryDefinitionAndCanonicalLocale() throws Exception {
    setup();
    Files.createDirectories(root.resolve("dialogues/Ä space"));
    Files.writeString(root.resolve("dialogues/Ä space/unused.json"), "{}");
    Files.writeString(root.resolve("texts/de-DE.json"), "{}");
    var r = ProjectInputs.read(root);
    assertTrue(r.problems().isEmpty());
    assertEquals(Set.of("dialogues/Ä space/unused.json"), r.sources().dialogues().keySet());
    assertEquals(Set.of("en", "de-DE"), r.sources().texts().keySet());
    Files.writeString(root.resolve("texts/de-de.json"), "{}");
    assertEquals("CLI_PATH", code(ProjectInputs.read(root)));
  }

  @Test
  void nestedTextRejected() throws Exception {
    setup();
    Files.createDirectories(root.resolve("texts/nested"));
    Files.writeString(root.resolve("texts/nested/de.json"), "{}");
    assertEquals("CLI_PATH", code(ProjectInputs.read(root)));
  }

  @Test
  void symlinkAndSpecialFileRejected() throws Exception {
    setup();
    try {
      Files.createSymbolicLink(root.resolve("dialogues/link.json"), root.resolve("texts/en.json"));
    } catch (UnsupportedOperationException | java.io.IOException e) {
      Assumptions.abort("symlinks unavailable");
    }
    assertEquals("CLI_SYMLINK", code(ProjectInputs.read(root)));
  }

  @Test
  void discoveryLimitFailsInsteadOfTruncating() throws Exception {
    setup();
    for (int i = 0; i < 10001; i++) Files.createFile(root.resolve("dialogues/file" + i));
    assertEquals("CLI_DISCOVERY_LIMIT", code(ProjectInputs.read(root)));
  }

  @Test
  void depthLimitFails() throws Exception {
    setup();
    Path p = root.resolve("dialogues");
    for (int i = 0; i < 33; i++) p = Files.createDirectory(p.resolve("d"));
    Files.writeString(p.resolve("deep.json"), "{}");
    assertEquals("CLI_DISCOVERY_LIMIT", code(ProjectInputs.read(root)));
  }

  @Test
  void growingFileIsBounded() throws Exception {
    setup();
    Path p = root.resolve("dialogues/a.json");
    Files.writeString(p, "{}");
    var result =
        ProjectInputs.read(
            root,
            path -> {
              if (path.equals(p)) Files.write(path, new byte[1048577]);
            });
    assertEquals("CLI_INPUT_LIMIT", code(result));
  }

  @Test
  void changedFileRejected() throws Exception {
    setup();
    Path p = root.resolve("dialogues/a.json");
    Files.writeString(p, "{}");
    var result =
        ProjectInputs.read(
            root,
            path -> {
              if (path.equals(p)) {
                Files.delete(p);
                Files.writeString(p, "{ }");
              }
            });
    assertEquals("CLI_INPUT_CHANGED", code(result));
  }

  @Test
  void symlinkReplacementRejected() throws Exception {
    setup();
    Path p = root.resolve("dialogues/a.json");
    Files.writeString(p, "{}");
    try {
      Files.createSymbolicLink(root.resolve("probe"), root.resolve("texts/en.json"));
      Files.delete(root.resolve("probe"));
    } catch (UnsupportedOperationException | java.io.IOException e) {
      Assumptions.abort("symlinks unavailable");
    }
    var result =
        ProjectInputs.read(
            root,
            path -> {
              if (path.equals(p)) {
                Files.delete(p);
                Files.createSymbolicLink(p, root.resolve("texts/en.json"));
              }
            });
    assertEquals("CLI_SYMLINK", code(result));
  }

  @Test
  void missingHostAndDirectoryAreIoErrors() throws Exception {
    setup();
    Files.delete(root.resolve("context/host-catalog.json"));
    var r = ProjectInputs.read(root);
    assertEquals("CLI_IO", code(r));
    assertEquals("context/host-catalog.json", r.problems().getFirst().file());
    assertNull(r.problems().getFirst().path());
    Files.delete(root.resolve("dialogues"));
    assertEquals("CLI_IO", code(ProjectInputs.read(root)));
  }

  @Test
  void emptyDefinitionsReachValidator() throws Exception {
    setup();
    var r = ProjectInputs.read(root);
    assertNotNull(r.sources());
    assertTrue(r.sources().dialogues().isEmpty());
  }

  @Test
  void oversizedAndExcessFilesRejected() throws Exception {
    setup();
    Files.write(root.resolve("dialogues/large.json"), new byte[1048577]);
    assertEquals("CLI_INPUT_LIMIT", code(ProjectInputs.read(root)));
    Files.delete(root.resolve("dialogues/large.json"));
    for (int i = 0; i < 1025; i++)
      Files.writeString(root.resolve("dialogues/a" + i + ".json"), "{}");
    assertEquals("CLI_INPUT_LIMIT", code(ProjectInputs.read(root)));
  }

  @Test
  void specialFileDoesNotBlockReader() throws Exception {
    setup();
    Assumptions.assumeTrue(System.getProperty("os.name").equals("Linux"));
    var process =
        new ProcessBuilder("mkfifo", root.resolve("dialogues/pipe.json").toString()).start();
    assertEquals(0, process.waitFor());
    assertEquals("CLI_PATH", code(ProjectInputs.read(root)));
  }

  @Test
  void localeAndAggregateByteLimits() throws Exception {
    setup();
    for (int i = 0; i < 64; i++)
      Files.writeString(root.resolve("texts/en-x-l" + i + ".json"), "{}");
    assertEquals("CLI_INPUT_LIMIT", code(ProjectInputs.read(root)));
    try (var files = Files.list(root.resolve("texts"))) {
      for (var p : files.toList()) Files.delete(p);
    }
    for (int i = 0; i < 5; i++)
      Files.write(root.resolve("texts/en-x-l" + i + ".json"), new byte[4 * 1024 * 1024]);
    assertEquals("CLI_INPUT_LIMIT", code(ProjectInputs.read(root)));
  }
}
