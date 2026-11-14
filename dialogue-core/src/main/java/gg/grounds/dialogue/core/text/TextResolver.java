package gg.grounds.dialogue.core.text;

import gg.grounds.dialogue.core.definition.ArgumentValue;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@FunctionalInterface
public interface TextResolver {
  record TextRequest(String key, Map<String, ArgumentValue> arguments, LocaleContext locale) {
    public TextRequest {
      arguments = Map.copyOf(arguments);
    }
  }

  CompletionStage<ResolvedText> resolve(TextRequest request);

  static TextResolver catalogue(TextCatalog catalog, String defaultTag) {
    return req -> {
      try {
        String tag = req.locale().tag();
        if (!catalog.locales().containsKey(tag)) tag = Locale.forLanguageTag(tag).getLanguage();
        if (!catalog.locales().containsKey(tag)) tag = req.locale().fallbackTag();
        if (!catalog.locales().containsKey(tag)) tag = defaultTag;
        String value = catalog.locales().getOrDefault(tag, Map.of()).get(req.key());
        if (value == null) throw new IllegalArgumentException("TEXT_MISSING: " + req.key());
        var matcher = Pattern.compile("\\{\\{|\\}\\}|\\{([a-zA-Z0-9_.-]+)\\}").matcher(value);
        var out = new StringBuilder();
        while (matcher.find()) {
          String replacement;
          if (matcher.group().equals("{{")) replacement = "{";
          else if (matcher.group().equals("}}")) replacement = "}";
          else {
            var arg = req.arguments().get(matcher.group(1));
            if (arg == null)
              throw new IllegalArgumentException("TEXT_ARGUMENT_MISSING: " + matcher.group(1));
            replacement =
                switch (arg) {
                  case ArgumentValue.Text t -> t.value();
                  case ArgumentValue.Number n -> n.value().stripTrailingZeros().toPlainString();
                  case ArgumentValue.Flag f -> Boolean.toString(f.value());
                  case ArgumentValue.Nil nil -> "";
                };
          }
          matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return CompletableFuture.completedFuture(new ResolvedText(out.toString(), tag));
      } catch (RuntimeException e) {
        return CompletableFuture.failedFuture(e);
      }
    };
  }
}
