package gg.grounds.dialogue.core.text

import gg.grounds.dialogue.core.definition.ArgumentValue
import java.math.BigDecimal
import java.util.concurrent.CompletionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class TextExpansionRegressionTest {
    @Test
    fun i15ExpansionIsBoundedBeforeAppending() {
        val resolver =
            TextResolver.catalogue(TextCatalog(mapOf("en" to mapOf("line" to "{x}{x}"))), "en")
        val future =
            resolver
                .resolve(
                    TextResolver.TextRequest(
                        "line",
                        mapOf("x" to ArgumentValue.of("x".repeat(8193))),
                        LocaleContext("en", 0, "en"),
                    )
                )
                .toCompletableFuture()
        assertTrue(future.isCompletedExceptionally)
        assertEquals(
            "TEXT_OUTPUT_LIMIT",
            assertThrows<CompletionException> { future.join() }.cause?.message,
        )
    }

    @Test
    fun i15NumericExpansionChecksExponentFirst() {
        val resolver =
            TextResolver.catalogue(TextCatalog(mapOf("en" to mapOf("line" to "{x}"))), "en")
        val future =
            resolver
                .resolve(
                    TextResolver.TextRequest(
                        "line",
                        mapOf("x" to ArgumentValue.Number(BigDecimal("1e1000000"))),
                        LocaleContext("en", 0, "en"),
                    )
                )
                .toCompletableFuture()
        assertEquals(
            "NUMBER_LIMIT",
            assertThrows<CompletionException> { future.join() }.cause?.message,
        )
    }
}
