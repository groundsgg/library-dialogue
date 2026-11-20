package gg.grounds.dialogue.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import gg.grounds.dialogue.json.DialogueCatalogLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class DialogueCliTest {
    @TempDir lateinit var root: Path

    private class Run(val code: Int, val out: ByteArray, val err: ByteArray) {
        fun stdout() = out.decodeToString()

        fun stderr() = err.decodeToString()

        fun json(): JsonObject = JsonParser.parseString(stdout()).asJsonObject
    }

    private fun run(vararg args: String): Run {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val code = DialogueCli.run(arrayOf(*args), stdout, stderr)
        return Run(code, stdout.toByteArray(), stderr.toByteArray())
    }

    private fun project() {
        Files.walk(EXAMPLE).use { files ->
            files.forEach { path ->
                val destination = root.resolve(EXAMPLE.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(destination)
                else Files.copy(path, destination)
            }
        }
    }

    private fun validationArgs() =
        arrayOf("validate", "--root", root.toString(), "--format", "json")

    private fun validate() = run(*validationArgs())

    @Test
    fun validProjectHasSamePinAsPublicLoader() {
        project()
        val result = validate()
        assertEquals(0, result.code, result.stdout())
        val json = result.json()
        assertEquals("valid", json["status"].asString)
        assertEquals(1, json["reportVersion"].asInt)
        assertEquals(1, json["formatVersion"].asInt)
        val source = checkNotNull(ProjectInputs.read(root).sources())
        val loaded = DialogueCatalogLoader().reload(source)
        assertEquals(
            checkNotNull(loaded.candidate()).contentPin().sha256,
            json["contentPin"].asString,
        )
        assertArrayEquals(result.out, validate().out)
        assertEquals("", result.stderr())
    }

    @Test
    fun malformedJsonAndMissingKeysReturnOne() {
        project()
        Files.writeString(root.resolve("dialogues/mechanic.json"), "{")
        assertEquals(1, validate().code)
        Files.writeString(root.resolve("dialogues/mechanic.json"), "{}")
        assertEquals(1, validate().code)
    }

    @Test
    fun usageOrIoReturnsTwoWithoutPathsOrStacktrace() {
        val result = validate()
        assertEquals(2, result.code)
        assertEquals("error", result.json()["status"].asString)
        assertTrue(result.json()["contentPin"].isJsonNull)
        assertFalse(result.stdout().contains(root.toString()))
        assertFalse(result.stdout().contains("Exception"))
        assertEquals(2, run("unknown").code)
        assertEquals(0, run("--help").code)
        assertEquals(0, run("--version").code)
    }

    @Test
    fun jsonModeSurvivesUnknownArgumentBeforeOrAfter() {
        for (args in
            listOf(arrayOf("--wat", "--format", "json"), arrayOf("--format", "json", "--wat"))) {
            val result = run(*args)
            assertEquals(2, result.code)
            assertEquals("error", result.json()["status"].asString)
            assertEquals("", result.stderr())
        }
        val contradictory = run("--format", "json", "--format", "text")
        assertEquals(2, contradictory.code)
        assertEquals("", contradictory.stdout())
        assertFalse(contradictory.stderr().isEmpty())
        assertEquals("", run("--format", "yaml").stdout())
    }

    @Test
    fun emptyDefinitionsAreContentError() {
        project()
        Files.delete(root.resolve("dialogues/mechanic.json"))
        val result = validate()
        assertEquals(1, result.code)
        assertEquals(
            "CATALOGUE_LIMIT",
            result.json().getAsJsonArray("problems")[0].asJsonObject["code"].asString,
        )
    }

    @Test
    fun outputFailureNeverReportsSuccess() {
        project()
        val out =
            object : OutputStream() {
                override fun write(b: Int) {
                    throw IOException("broken")
                }
            }
        assertEquals(2, DialogueCli.run(validationArgs(), out, ByteArrayOutputStream()))
    }

    @Test
    fun warningsStayValid() {
        project()
        val definition = root.resolve("dialogues/mechanic.json")
        val json = JsonParser.parseString(Files.readString(definition)).asJsonObject
        val node =
            JsonObject().apply {
                addProperty("type", "end")
                addProperty("reason", "completed")
            }
        json.getAsJsonObject("nodes").add("unused", node)
        Files.writeString(definition, json.toString())
        val result = validate()
        assertEquals(0, result.code, result.stdout())
        assertTrue(result.stdout().contains("NODE_UNREACHABLE"))
    }

    @Test
    fun realChildProcessHasExactExitAndBuildVersion() {
        project()
        val good = child(*validationArgs()).start()
        val output = good.inputStream.readAllBytes().decodeToString()
        assertEquals(0, good.waitFor())
        assertEquals("valid", JsonParser.parseString(output).asJsonObject["status"].asString)

        Files.writeString(root.resolve("dialogues/mechanic.json"), "{")
        val bad = child(*validationArgs()).start()
        bad.inputStream.readAllBytes()
        assertEquals(1, bad.waitFor())

        val version = child("--version").start()
        assertEquals(
            VersionInfo.current().toolVersion,
            version.inputStream.readAllBytes().decodeToString().trim(),
        )
        assertEquals(0, version.waitFor())
    }

    @Test
    fun realMainCannotClaimSuccessWhenStdoutIsFull() {
        assumeTrue(Files.exists(Path.of("/dev/full")))
        project()
        val child = child(*validationArgs()).redirectOutput(File("/dev/full")).start()
        assertEquals(2, child.waitFor())
    }

    private fun child(vararg args: String) =
        ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("cli.runtimeClasspath"),
            DialogueCli::class.java.name,
            *args,
        )

    companion object {
        private val EXAMPLE = Path.of("../examples/headless-dialogue/src/main/resources")
    }
}
