package gg.grounds.dialogue.core.text;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.ArgumentValue;
import java.util.*;
import org.junit.jupiter.api.Test;

class TextResolverTest {
  @Test
  void argumentsAreResolvedOnceAsPlainText() {
    var resolver =
        TextResolver.catalogue(
            new TextCatalog(Map.of("en", Map.of("line", "Hi {name}; {count}; {{literal}}"))), "en");
    var result =
        resolver
            .resolve(
                new TextResolver.TextRequest(
                    "line",
                    Map.of(
                        "name", ArgumentValue.of("{count} /op @s"), "count", ArgumentValue.of(3)),
                    new LocaleContext("en-US", 0, "en")))
            .toCompletableFuture()
            .join();
    assertEquals("Hi {count} /op @s; 3; {literal}", result.plain());
    assertEquals("en", result.localeTag());
  }
}
