package gg.grounds.dialogue.cli

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class CliArgumentsTest {
    @Test
    fun validatesCompleteCommandInsteadOfIgnoringTrailingArguments() {
        assertFalse(CliArguments.parse(arrayOf("--help", "junk")).problems().isEmpty())
        assertFalse(
            CliArguments.parse(arrayOf("validate", "--root", "a", "--root", "b"))
                .problems()
                .isEmpty()
        )
        assertTrue(
            CliArguments.parse(arrayOf("validate", "--root", "a space", "--format", "json"))
                .problems()
                .isEmpty()
        )
    }
}
