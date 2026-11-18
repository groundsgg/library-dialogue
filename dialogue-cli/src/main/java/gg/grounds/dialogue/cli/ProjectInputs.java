package gg.grounds.dialogue.cli;

import gg.grounds.dialogue.core.validation.ValidationReport.*;
import gg.grounds.dialogue.json.DialogueCatalogLoader.ContentSources;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Read-only, bounded snapshot. Not an atomic publication proof on a hostile filesystem. */
final class ProjectInputs {
  record Result(ContentSources sources, List<Problem> problems) {
    Result {
      problems = List.copyOf(problems);
    }
  }

  @FunctionalInterface
  interface BeforeRead {
    void accept(Path path) throws IOException;
  }

  private static final int TOTAL = 16 * 1024 * 1024;
  private final Path root;
  private final BeforeRead beforeRead;
  private int visited, bytes;

  private ProjectInputs(Path root, BeforeRead beforeRead) {
    this.root = root.toAbsolutePath().normalize();
    this.beforeRead = beforeRead;
  }

  static Result read(Path root) {
    return read(root, p -> {});
  }

  static Result read(Path root, BeforeRead beforeRead) {
    var reader = new ProjectInputs(root, beforeRead);
    try {
      return new Result(reader.load(), List.of());
    } catch (InputFailure e) {
      return new Result(
          null, List.of(new Problem(e.code, Severity.ERROR, e.file, null, null, e.getMessage())));
    }
  }

  private static final class InputFailure extends RuntimeException {
    final String code, file;

    InputFailure(String code, String file, String message) {
      super(message);
      this.code = code;
      this.file = file;
    }
  }

  private InputFailure failure(String code, Path path, String message) {
    return new InputFailure(code, path == null ? null : relative(path), message);
  }

  private String relative(Path path) {
    Path normalized = path.toAbsolutePath().normalize();
    if (!normalized.startsWith(root)) return null;
    return root.relativize(normalized).toString().replace(File.separatorChar, '/');
  }

  private BasicFileAttributes attributes(Path path) {
    if (!path.toAbsolutePath().normalize().startsWith(root))
      throw failure("CLI_PATH", null, "Input escapes project root");
    try {
      var a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (a.isSymbolicLink()) throw failure("CLI_SYMLINK", path, "Symbolic links are not allowed");
      if (!a.isRegularFile() && !a.isDirectory())
        throw failure("CLI_PATH", path, "Special files are not allowed");
      return a;
    } catch (IOException | SecurityException e) {
      throw failure("CLI_IO", path, "Cannot inspect input");
    }
  }

  private void directory(Path path) {
    if (!attributes(path).isDirectory()) throw failure("CLI_PATH", path, "Expected directory");
  }

  private ContentSources load() {
    directory(root);
    directory(root.resolve("dialogues"));
    directory(root.resolve("texts"));
    directory(root.resolve("context"));
    var definitions = new TreeMap<String, byte[]>();
    var texts = new TreeMap<String, byte[]>();
    for (Path path : discover(root.resolve("dialogues"))) {
      if (definitions.size() == 1024)
        throw failure("CLI_INPUT_LIMIT", path, "Too many dialogue definitions");
      definitions.put(relative(path), readFile(path, 1024 * 1024));
    }
    for (Path path : discover(root.resolve("texts"))) {
      if (!path.getParent().equals(root.resolve("texts")))
        throw failure("CLI_PATH", path, "Text files must be directly under texts");
      String name = path.getFileName().toString();
      String tag = name.substring(0, name.length() - 5);
      try {
        var locale = new Locale.Builder().setLanguageTag(tag).build();
        if (locale.getLanguage().isEmpty() || !locale.toLanguageTag().equals(tag))
          throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        throw failure("CLI_PATH", path, "Expected canonical language tag");
      }
      if (texts.size() == 64) throw failure("CLI_INPUT_LIMIT", path, "Too many locale files");
      texts.put(tag, readFile(path, 4 * 1024 * 1024));
    }
    return new ContentSources(
        definitions, texts, readFile(root.resolve("context/host-catalog.json"), TOTAL));
  }

  private List<Path> discover(Path start) {
    var found = new ArrayList<Path>();
    walk(start, 0, found);
    found.sort(Comparator.comparing(this::relative));
    return found;
  }

  private void walk(Path path, int depth, List<Path> found) {
    if (++visited > 10000 || depth > 32)
      throw failure("CLI_DISCOVERY_LIMIT", path, "Discovery limit exceeded");
    var attrs = attributes(path);
    if (attrs.isDirectory()) {
      // Do not materialize an unbounded directory listing before enforcing the entry limit.
      try (var stream = Files.newDirectoryStream(path)) {
        for (var child : stream) walk(child, depth + 1, found);
      } catch (IOException | DirectoryIteratorException | SecurityException e) {
        throw failure("CLI_IO", path, "Cannot enumerate input directory");
      }
    } else if (path.getFileName().toString().endsWith(".json")) found.add(path);
  }

  private byte[] readFile(Path path, int limit) {
    var before = attributes(path);
    if (!before.isRegularFile()) throw failure("CLI_PATH", path, "Expected regular file");
    int budget = Math.min(limit, TOTAL - bytes);
    if (before.size() > budget) throw failure("CLI_INPUT_LIMIT", path, "Input byte limit exceeded");
    try {
      beforeRead.accept(path);
      var opening = attributes(path);
      if (!opening.isRegularFile()) throw failure("CLI_PATH", path, "Expected regular file");
      var output = new ByteArrayOutputStream();
      try (var channel =
          Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
        var buffer = ByteBuffer.allocate(8192);
        while (true) {
          buffer.clear();
          buffer.limit(Math.min(buffer.capacity(), budget - output.size() + 1));
          int n = channel.read(buffer);
          if (n < 0) break;
          if (output.size() + n > budget)
            throw failure("CLI_INPUT_LIMIT", path, "Input byte limit exceeded");
          output.write(buffer.array(), 0, n);
        }
      }
      var after = attributes(path);
      if (!after.isRegularFile()
          || before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !Objects.equals(before.fileKey(), after.fileKey())
          || output.size() != after.size())
        throw failure("CLI_INPUT_CHANGED", path, "Input changed while being read");
      bytes += output.size();
      return output.toByteArray();
    } catch (IOException | SecurityException e) {
      if (Files.isSymbolicLink(path))
        throw failure("CLI_SYMLINK", path, "Symbolic links are not allowed");
      throw failure("CLI_IO", path, "Cannot read input");
    }
  }
}
