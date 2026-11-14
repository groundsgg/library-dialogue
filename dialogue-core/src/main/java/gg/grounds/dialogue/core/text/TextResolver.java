package gg.grounds.dialogue.core.text;

import gg.grounds.dialogue.core.definition.ArgumentValue;
import java.util.*;
import java.util.concurrent.*;

@FunctionalInterface
public interface TextResolver {
  record TextRequest(String key, Map<String, ArgumentValue> arguments, LocaleContext locale) {
    public TextRequest {
      arguments = Map.copyOf(arguments);
    }
  }

  CompletionStage<ResolvedText> resolve(TextRequest request);

  static TextResolver catalogue(TextCatalog catalog, String defaultTag) {
    return new CatalogueTextResolver(catalog, defaultTag);
  }
}
