package gg.grounds.dialogue.core.text;

import gg.grounds.dialogue.core.definition.ArgumentValue;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Bounded single-pass plain-text substitution with explicit per-key locale fallback. */
final class CatalogueTextResolver implements TextResolver {
  private static final int LIMIT = 16384;
  private static final Pattern TOKEN = Pattern.compile("\\{\\{|\\}\\}|\\{([a-zA-Z0-9_.-]+)\\}");
  private final TextCatalog catalog;
  private final String defaultTag;

  CatalogueTextResolver(TextCatalog catalog, String defaultTag) {
    this.catalog = Objects.requireNonNull(catalog);
    this.defaultTag = Objects.requireNonNull(defaultTag);
  }

  public CompletionStage<ResolvedText> resolve(TextRequest request) {
    try {
      String value = null, tag = null;
      var chain =
          new LinkedHashSet<String>(
              List.of(
                  request.locale().tag(),
                  Locale.forLanguageTag(request.locale().tag()).getLanguage(),
                  request.locale().fallbackTag(),
                  defaultTag));
      for (String candidate : chain) {
        value = catalog.locales().getOrDefault(candidate, Map.of()).get(request.key());
        if (value != null) {
          tag = candidate;
          break;
        }
      }
      if (value == null) throw new IllegalArgumentException("TEXT_MISSING: " + request.key());
      if (value.length() > LIMIT * 2 || value.codePointCount(0, value.length()) > LIMIT)
        throw new IllegalArgumentException("TEXT_OUTPUT_LIMIT");
      var out = new StringBuilder(Math.min(value.length(), LIMIT));
      var matcher = TOKEN.matcher(value);
      int end = 0, remaining = LIMIT;
      while (matcher.find()) {
        remaining = append(out, value.substring(end, matcher.start()), remaining);
        String replacement;
        if (matcher.group().equals("{{")) replacement = "{";
        else if (matcher.group().equals("}}")) replacement = "}";
        else {
          var argument = request.arguments().get(matcher.group(1));
          if (argument == null)
            throw new IllegalArgumentException("TEXT_ARGUMENT_MISSING: " + matcher.group(1));
          replacement =
              switch (argument) {
                case ArgumentValue.Text t -> t.value();
                case ArgumentValue.Flag f -> Boolean.toString(f.value());
                case ArgumentValue.Nil nil -> "";
                case ArgumentValue.Number n -> {
                  var normalized = n.value().stripTrailingZeros();
                  if (normalized.precision() > 128
                      || Math.abs((long) normalized.precision() - normalized.scale() - 1) > 308)
                    throw new IllegalArgumentException("NUMBER_LIMIT");
                  yield normalized.toPlainString();
                }
              };
        }
        remaining = append(out, replacement, remaining);
        end = matcher.end();
      }
      append(out, value.substring(end), remaining);
      return CompletableFuture.completedFuture(new ResolvedText(out.toString(), tag));
    } catch (RuntimeException error) {
      return CompletableFuture.failedFuture(error);
    }
  }

  private static int append(StringBuilder out, String value, int remaining) {
    if (value.length() > remaining * 2) throw new IllegalArgumentException("TEXT_OUTPUT_LIMIT");
    int size = value.codePointCount(0, value.length());
    if (size > remaining) throw new IllegalArgumentException("TEXT_OUTPUT_LIMIT");
    if (!gg.grounds.dialogue.core.validation.DialogueValidator.validUnicode(value))
      throw new IllegalArgumentException("UNICODE_INVALID");
    out.append(value);
    return remaining - size;
  }
}
