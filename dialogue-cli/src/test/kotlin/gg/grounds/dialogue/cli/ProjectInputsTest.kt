package gg.grounds.dialogue.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class ProjectInputsTest {
    @TempDir lateinit var root: Path

    private fun setup() {
        Files.createDirectories(root.resolve("dialogues"))
        Files.createDirectories(root.resolve("texts"))
        Files.createDirectories(root.resolve("context"))
        Files.writeString(root.resolve("context/host-catalog.json"), "{}")
        Files.writeString(root.resolve("texts/en.json"), "{}")
    }

    private fun code(result: ProjectInputs.Result): String {
        assertNull(result.sources())
        return result.problems().first().code
    }

    @Test
    fun readsEveryDefinitionAndCanonicalLocale() {
        setup()
        Files.createDirectories(root.resolve("dialogues/Ä space"))
        Files.writeString(root.resolve("dialogues/Ä space/unused.json"), "{}")
        Files.writeString(root.resolve("texts/de-DE.json"), "{}")
        val result = ProjectInputs.read(root)
        assertTrue(result.problems().isEmpty())
        val sources = checkNotNull(result.sources())
        assertEquals(setOf("dialogues/Ä space/unused.json"), sources.dialogues().keys)
        assertEquals(setOf("en", "de-DE"), sources.texts().keys)
        Files.delete(root.resolve("texts/de-DE.json"))
        Files.writeString(root.resolve("texts/de-de.json"), "{}")
        assertEquals("CLI_PATH", code(ProjectInputs.read(root)))
    }

    @Test
    fun nestedTextRejected() {
        setup()
        Files.createDirectories(root.resolve("texts/nested"))
        Files.writeString(root.resolve("texts/nested/de.json"), "{}")
        assertEquals("CLI_PATH", code(ProjectInputs.read(root)))
    }

    @Test
    fun symlinkAndSpecialFileRejected() {
        setup()
        createLinkOrAbort(root.resolve("dialogues/link.json"), root.resolve("texts/en.json"))
        assertEquals("CLI_SYMLINK", code(ProjectInputs.read(root)))
    }

    @Test
    fun discoveryLimitFailsInsteadOfTruncating() {
        setup()
        repeat(10_001) { Files.createFile(root.resolve("dialogues/file$it")) }
        assertEquals("CLI_DISCOVERY_LIMIT", code(ProjectInputs.read(root)))
    }

    @Test
    fun depthLimitFails() {
        setup()
        var directory = root.resolve("dialogues")
        repeat(33) { directory = Files.createDirectory(directory.resolve("d")) }
        Files.writeString(directory.resolve("deep.json"), "{}")
        assertEquals("CLI_DISCOVERY_LIMIT", code(ProjectInputs.read(root)))
    }

    @Test
    fun growingFileIsBounded() {
        setup()
        val definition = root.resolve("dialogues/a.json")
        Files.writeString(definition, "{}")
        val result =
            ProjectInputs.read(root) { path ->
                if (path == definition) Files.write(path, ByteArray(1_048_577))
            }
        assertEquals("CLI_INPUT_LIMIT", code(result))
    }

    @Test
    fun changedFileRejected() {
        setup()
        val definition = root.resolve("dialogues/a.json")
        Files.writeString(definition, "{}")
        val result =
            ProjectInputs.read(root) { path ->
                if (path == definition) {
                    Files.delete(definition)
                    Files.writeString(definition, "{ }")
                }
            }
        assertEquals("CLI_INPUT_CHANGED", code(result))
    }

    @Test
    fun symlinkReplacementRejected() {
        setup()
        val definition = root.resolve("dialogues/a.json")
        val target = root.resolve("texts/en.json")
        val probe = root.resolve("probe")
        Files.writeString(definition, "{}")
        createLinkOrAbort(probe, target)
        Files.delete(probe)
        val result =
            ProjectInputs.read(root) { path ->
                if (path == definition) {
                    Files.delete(definition)
                    Files.createSymbolicLink(definition, target)
                }
            }
        assertEquals("CLI_SYMLINK", code(result))
    }

    @Test
    fun missingHostAndDirectoryAreIoErrors() {
        setup()
        Files.delete(root.resolve("context/host-catalog.json"))
        val result = ProjectInputs.read(root)
        assertEquals("CLI_IO", code(result))
        assertEquals("context/host-catalog.json", result.problems().first().file)
        assertNull(result.problems().first().path)
        Files.delete(root.resolve("dialogues"))
        assertEquals("CLI_IO", code(ProjectInputs.read(root)))
    }

    @Test
    fun emptyDefinitionsReachValidator() {
        setup()
        val result = ProjectInputs.read(root)
        assertTrue(checkNotNull(result.sources()).dialogues().isEmpty())
    }

    @Test
    fun oversizedAndExcessFilesRejected() {
        setup()
        val oversized = root.resolve("dialogues/large.json")
        Files.write(oversized, ByteArray(1_048_577))
        assertEquals("CLI_INPUT_LIMIT", code(ProjectInputs.read(root)))
        Files.delete(oversized)
        repeat(1_025) { Files.writeString(root.resolve("dialogues/a$it.json"), "{}") }
        assertEquals("CLI_INPUT_LIMIT", code(ProjectInputs.read(root)))
    }

    @Test
    fun specialFileDoesNotBlockReader() {
        setup()
        assumeTrue(System.getProperty("os.name") == "Linux")
        val process =
            ProcessBuilder("mkfifo", root.resolve("dialogues/pipe.json").toString()).start()
        assertEquals(0, process.waitFor())
        assertEquals("CLI_PATH", code(ProjectInputs.read(root)))
    }

    @Test
    fun localeAndAggregateByteLimits() {
        setup()
        repeat(64) { Files.writeString(root.resolve("texts/en-x-l$it.json"), "{}") }
        assertEquals("CLI_INPUT_LIMIT", code(ProjectInputs.read(root)))
        Files.list(root.resolve("texts")).use { files -> files.forEach(Files::delete) }
        repeat(5) { Files.write(root.resolve("texts/en-x-l$it.json"), ByteArray(4 * 1024 * 1024)) }
        assertEquals("CLI_INPUT_LIMIT", code(ProjectInputs.read(root)))
    }

    @Test
    fun ambiguousNativeFilenamesCannotDropDefinitions() {
        assumeTrue(System.getProperty("os.name") == "Linux")
        setup()
        val script =
            """
            import os, sys
            root = os.fsencode(sys.argv[1])
            for n in (254, 255):
                fd = os.open(root + b'/dialogues/' + bytes([n]) + b'.json', os.O_CREAT | os.O_WRONLY, 0o600)
                try:
                    os.write(fd, b'{}')
                finally:
                    os.close(fd)
            """
                .trimIndent()
        val child = ProcessBuilder("python3", "-c", script, root.toString()).start()
        assertEquals(0, child.waitFor())
        assertEquals("CLI_PATH", code(ProjectInputs.read(root)))
    }

    private fun createLinkOrAbort(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (cause: Exception) {
            if (cause !is UnsupportedOperationException && cause !is IOException) throw cause
            abort<Unit>("symlinks unavailable")
        }
    }
}
