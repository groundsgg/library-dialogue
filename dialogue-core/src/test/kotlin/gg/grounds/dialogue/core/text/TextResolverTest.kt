package gg.grounds.dialogue.core.text

import gg.grounds.dialogue.core.definition.ArgumentValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class TextResolverTest {
    @Test
    fun argumentsAreResolvedOnceAsPlainText() {
        val resolver =
            TextResolver.catalogue(
                TextCatalog(mapOf("en" to mapOf("line" to "Hi {name}; {count}; {{literal}}"))),
                "en",
            )
        val result =
            resolver
                .resolve(
                    TextResolver.TextRequest(
                        "line",
                        mapOf(
                            "name" to ArgumentValue.of("{count} /op @s"),
                            "count" to ArgumentValue.of(3L),
                        ),
                        LocaleContext("en-US", 0, "en"),
                    )
                )
                .toCompletableFuture()
                .join()
        assertEquals("Hi {count} /op @s; 3; {literal}", result.plain)
        assertEquals("en", result.localeTag)
    }
}
