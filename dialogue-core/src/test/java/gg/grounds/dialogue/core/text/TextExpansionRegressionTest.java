package gg.grounds.dialogue.core.text;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.ArgumentValue;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class TextExpansionRegressionTest {
  @Test
  void i15ExpansionIsBoundedBeforeAppending() {
    var resolver =
        TextResolver.catalogue(new TextCatalog(Map.of("en", Map.of("line", "{x}{x}"))), "en");
    var f =
        resolver.resolve(
            new TextResolver.TextRequest(
                "line",
                Map.of("x", ArgumentValue.of("x".repeat(8193))),
                new LocaleContext("en", 0, "en")));
    assertTrue(f.toCompletableFuture().isCompletedExceptionally());
    var error =
        assertThrows(
            java.util.concurrent.CompletionException.class, () -> f.toCompletableFuture().join());
    assertEquals("TEXT_OUTPUT_LIMIT", error.getCause().getMessage());
  }

  @Test
  void i15NumericExpansionChecksExponentFirst() {
    var resolver =
        TextResolver.catalogue(new TextCatalog(Map.of("en", Map.of("line", "{x}"))), "en");
    var f =
        resolver.resolve(
            new TextResolver.TextRequest(
                "line",
                Map.of("x", new ArgumentValue.Number(new BigDecimal("1e1000000"))),
                new LocaleContext("en", 0, "en")));
    var e =
        assertThrows(
            java.util.concurrent.CompletionException.class, () -> f.toCompletableFuture().join());
    assertEquals("NUMBER_LIMIT", e.getCause().getMessage());
  }
}
