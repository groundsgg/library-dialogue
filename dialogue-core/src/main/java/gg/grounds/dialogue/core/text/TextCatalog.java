package gg.grounds.dialogue.core.text;

import gg.grounds.dialogue.core.validation.*;
import gg.grounds.dialogue.core.validation.ValidationReport.*;
import java.util.*;

/** Immutable translations, with a single bounded validation memo for shared catalogues. */
public final class TextCatalog {
  private final Map<String, Map<String, String>> locales;
  private volatile Cached cached;
  private volatile ContentPin fingerprint;

  private record Cached(ValidationLimits limits, List<Problem> problems) {}

  public TextCatalog(Map<String, Map<String, String>> source) {
    var copy = new LinkedHashMap<String, Map<String, String>>();
    source.forEach((k, v) -> copy.put(k, Map.copyOf(v)));
    locales = Collections.unmodifiableMap(copy);
  }

  public Map<String, Map<String, String>> locales() {
    return locales;
  }

  public ContentPin contentPin() {
    var pin = fingerprint;
    if (pin == null) {
      pin = ContentFingerprint.hash(ContentFingerprint.canonicalBytes(locales));
      fingerprint = pin;
    }
    return pin;
  }

  public List<Problem> structuralProblems(ValidationLimits limits) {
    var previous = cached;
    if (previous != null && previous.limits().equals(limits)) return previous.problems();
    var problems = new ArrayList<Problem>();
    long bytes = 0;
    if (locales.size() > limits.locales())
      problem(problems, "LOCALE_LIMIT", "texts", "/", "Too many text files");
    for (var bundle : locales.entrySet()) {
      String file = "texts/" + bundle.getKey() + ".json";
      boolean safe = true;
      try {
        var locale = new Locale.Builder().setLanguageTag(bundle.getKey()).build();
        if (locale.getLanguage().isEmpty() || !locale.toLanguageTag().equals(bundle.getKey()))
          throw new IllegalArgumentException();
      } catch (RuntimeException e) {
        problem(problems, "LOCALE_INVALID", file, "/", "Invalid text locale");
        safe = false;
      }
      for (var entry : bundle.getValue().entrySet()) {
        String key = entry.getKey();
        if (key.isBlank()
            || key.codePointCount(0, key.length()) > limits.idCodepoints()
            || !key.matches("[a-zA-Z0-9_.:/-]+")) {
          problem(problems, "TEXT_KEY_INVALID", file, "/", "Invalid text key");
          safe = false;
        }
        String value = entry.getValue();
        if (!DialogueValidator.validUnicode(value)
            || value.codePointCount(0, value.length()) > limits.textCodepoints()) {
          problem(problems, "TEXT_LIMIT", file, "/" + key, "Invalid or oversized translation");
          safe = false;
        }
      }
      if (safe) {
        long size = ContentFingerprint.canonicalBytes(bundle.getValue()).length;
        bytes += size;
        if (size > limits.textFileBytes())
          problem(problems, "TEXT_FILE_LIMIT", file, "/", "Canonical text file exceeds budget");
      }
    }
    if (bytes > limits.textPackageBytes())
      problem(problems, "TEXT_PACKAGE_LIMIT", "texts", "/", "Text package exceeds budget");
    var result = List.copyOf(problems);
    cached = new Cached(limits, result);
    return result;
  }

  private static void problem(
      List<Problem> problems, String code, String file, String path, String message) {
    problems.add(new Problem(code, Severity.ERROR, file, path, null, message));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof TextCatalog t && locales.equals(t.locales);
  }

  @Override
  public int hashCode() {
    return locales.hashCode();
  }
}
