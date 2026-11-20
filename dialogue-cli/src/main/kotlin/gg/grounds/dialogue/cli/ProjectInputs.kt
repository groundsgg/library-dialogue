package gg.grounds.dialogue.cli

import gg.grounds.dialogue.core.validation.ValidationReport.Problem
import gg.grounds.dialogue.core.validation.ValidationReport.Severity
import gg.grounds.dialogue.json.DialogueCatalogLoader.ContentSources
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.TreeMap

/** Read-only, bounded snapshot. Files can still change after the snapshot is taken. */
internal class ProjectInputs private constructor(root: Path, private val beforeRead: BeforeRead) {
    private val root = root.toAbsolutePath().normalize()
    private var visited = 0
    private var bytes = 0

    class Result(private val sources: ContentSources?, problems: List<Problem>) {
        private val problems = java.util.List.copyOf(problems)

        fun sources(): ContentSources? = sources

        fun problems(): List<Problem> = problems
    }

    fun interface BeforeRead {
        @Throws(IOException::class) fun accept(path: Path)
    }

    private class InputFailure(val code: String, val file: String?, message: String) :
        RuntimeException(message)

    private fun failure(code: String, path: Path?, message: String) =
        InputFailure(code, path?.let(::relative), message)

    private fun relative(path: Path): String? {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(root)) return null
        return root.relativize(normalized).toString().replace(File.separatorChar, '/')
    }

    private fun attributes(path: Path): BasicFileAttributes {
        val name = path.fileName
        if (name != null && name != Path.of(name.toString())) {
            throw failure("CLI_PATH", path, "Input name cannot be represented unambiguously")
        }
        if (!path.toAbsolutePath().normalize().startsWith(root)) {
            throw failure("CLI_PATH", null, "Input escapes project root")
        }
        return try {
            val attributes =
                Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            if (attributes.isSymbolicLink)
                throw failure("CLI_SYMLINK", path, "Symbolic links are not allowed")
            if (!attributes.isRegularFile && !attributes.isDirectory) {
                throw failure("CLI_PATH", path, "Special files are not allowed")
            }
            attributes
        } catch (cause: Exception) {
            if (cause !is IOException && cause !is SecurityException) throw cause
            throw failure("CLI_IO", path, "Cannot inspect input")
        }
    }

    private fun directory(path: Path) {
        if (!attributes(path).isDirectory) throw failure("CLI_PATH", path, "Expected directory")
    }

    private fun load(): ContentSources {
        val dialoguesDirectory = root.resolve("dialogues")
        val textsDirectory = root.resolve("texts")
        directory(root)
        directory(dialoguesDirectory)
        directory(textsDirectory)
        directory(root.resolve("context"))

        val definitions = TreeMap<String, ByteArray>()
        discover(dialoguesDirectory).forEach { path ->
            if (definitions.size == 1024)
                throw failure("CLI_INPUT_LIMIT", path, "Too many dialogue definitions")
            val key = checkNotNull(relative(path))
            if (key in definitions) throw failure("CLI_PATH", path, "Ambiguous input source name")
            definitions[key] = readFile(path, 1024 * 1024)
        }

        val texts = TreeMap<String, ByteArray>()
        discover(textsDirectory).forEach { path ->
            if (path.parent != textsDirectory)
                throw failure("CLI_PATH", path, "Text files must be directly under texts")
            val tag = path.fileName.toString().removeSuffix(".json")
            try {
                val locale = Locale.Builder().setLanguageTag(tag).build()
                require(locale.language.isNotEmpty() && locale.toLanguageTag() == tag)
            } catch (_: IllegalArgumentException) {
                throw failure("CLI_PATH", path, "Expected canonical language tag")
            }
            if (texts.size == 64) throw failure("CLI_INPUT_LIMIT", path, "Too many locale files")
            texts[tag] = readFile(path, 4 * 1024 * 1024)
        }
        return ContentSources(
            definitions,
            texts,
            readFile(root.resolve("context/host-catalog.json"), TOTAL),
        )
    }

    private fun discover(start: Path): List<Path> {
        val found = ArrayList<Path>()
        walk(start, 0, found)
        found.sortBy(::relative)
        return found
    }

    private fun walk(path: Path, depth: Int, found: MutableList<Path>) {
        if (++visited > 10_000 || depth > 32) {
            throw failure("CLI_DISCOVERY_LIMIT", path, "Discovery limit exceeded")
        }
        if (attributes(path).isDirectory) {
            try {
                Files.newDirectoryStream(path).use { stream ->
                    stream.forEach { child -> walk(child, depth + 1, found) }
                }
            } catch (cause: Exception) {
                if (
                    cause !is IOException &&
                        cause !is DirectoryIteratorException &&
                        cause !is SecurityException
                )
                    throw cause
                throw failure("CLI_IO", path, "Cannot enumerate input directory")
            }
        } else if (path.fileName.toString().endsWith(".json")) {
            found.add(path)
        }
    }

    private fun readFile(path: Path, limit: Int): ByteArray {
        val before = attributes(path)
        if (!before.isRegularFile) throw failure("CLI_PATH", path, "Expected regular file")
        val budget = minOf(limit, TOTAL - bytes)
        if (before.size() > budget)
            throw failure("CLI_INPUT_LIMIT", path, "Input byte limit exceeded")

        try {
            beforeRead.accept(path)
            if (!attributes(path).isRegularFile)
                throw failure("CLI_PATH", path, "Expected regular file")
            val output = ByteArrayOutputStream()
            val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            Files.newByteChannel(path, options).use { channel ->
                val buffer = ByteBuffer.allocate(8192)
                while (true) {
                    buffer.clear()
                    buffer.limit(minOf(buffer.capacity(), budget - output.size() + 1))
                    val read = channel.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > budget)
                        throw failure("CLI_INPUT_LIMIT", path, "Input byte limit exceeded")
                    output.write(buffer.array(), 0, read)
                }
            }
            val after = attributes(path)
            if (
                !after.isRegularFile ||
                    before.size() != after.size() ||
                    before.lastModifiedTime() != after.lastModifiedTime() ||
                    before.fileKey() != after.fileKey() ||
                    output.size().toLong() != after.size()
            ) {
                throw failure("CLI_INPUT_CHANGED", path, "Input changed while being read")
            }
            bytes += output.size()
            return output.toByteArray()
        } catch (cause: Exception) {
            if (cause !is IOException && cause !is SecurityException) throw cause
            if (Files.isSymbolicLink(path))
                throw failure("CLI_SYMLINK", path, "Symbolic links are not allowed")
            throw failure("CLI_IO", path, "Cannot read input")
        }
    }

    companion object {
        private const val TOTAL = 16 * 1024 * 1024

        @JvmStatic fun read(root: Path): Result = read(root) {}

        @JvmStatic
        fun read(root: Path, beforeRead: BeforeRead): Result =
            try {
                Result(ProjectInputs(root, beforeRead).load(), emptyList())
            } catch (failure: InputFailure) {
                Result(
                    null,
                    listOf(
                        Problem(
                            failure.code,
                            Severity.ERROR,
                            failure.file,
                            null,
                            null,
                            checkNotNull(failure.message),
                        )
                    ),
                )
            }
    }
}
