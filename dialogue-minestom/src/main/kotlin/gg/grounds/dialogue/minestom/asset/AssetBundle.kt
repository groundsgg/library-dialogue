package gg.grounds.dialogue.minestom.asset

import com.google.gson.Gson
import com.google.gson.JsonParser
import gg.grounds.dialogue.core.validation.ContentFingerprint
import gg.grounds.dialogue.presentation.FontMetrics
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.HashMap
import java.util.OptionalInt
import java.util.Properties
import java.util.TreeMap

/** Immutable verified pack contribution. The host owns pack metadata, composition and hosting. */
class AssetBundle
private constructor(
    private val profile: MinecraftPackProfile,
    manifest: ByteArray,
    files: Map<String, ByteArray>,
    glyphs: Map<Int, Glyph>,
    private val pin: String,
) {
    @JvmRecord data class Glyph(val column: Int, val row: Int, val advance: Int)

    private val manifest = manifest.clone()
    private val files: Map<String, ByteArray> =
        Collections.unmodifiableMap(
            TreeMap<String, ByteArray>().also { copied ->
                files.forEach { (path, bytes) -> copied[path] = bytes.clone() }
            }
        )
    private val glyphs: Map<Int, Glyph> = Collections.unmodifiableMap(HashMap(glyphs))
    private val metrics: MinecraftFontMetrics
    private val hudMetrics: MinecraftFontMetrics
    private val hudBackdropAdvances: Map<String, Int>

    init {
        val advances = HashMap<Int, Int>()
        glyphs.forEach { (codePoint, glyph) -> advances[codePoint] = glyph.advance }
        advances[32] = 4
        advances[160] = 4
        metrics = MinecraftFontMetrics(pin, advances)

        val backdropAdvances = HashMap<String, Int>()
        val hud = files["assets/dialogue/hud_metrics.properties"]
        if (hud == null) {
            hudMetrics = metrics
        } else {
            if (hud.size > 65536) throw IllegalArgumentException("HUD_METRICS_LIMIT")
            val properties = Properties()
            try {
                properties.load(ByteArrayInputStream(hud))
            } catch (error: IOException) {
                throw IllegalArgumentException("HUD_METRICS", error)
            }
            val widths = HashMap<Int, Int>()
            val ink = HashMap<Int, FontMetrics.InkBounds>()
            for (key in properties.stringPropertyNames()) {
                when {
                    key.startsWith("glyph.") -> {
                        val codePoint = key.substring(6).toInt()
                        val width = properties.getProperty(key).toInt()
                        if (
                            !Character.isValidCodePoint(codePoint) ||
                                width < 1 ||
                                width > 17 ||
                                widths.size >= 512
                        ) {
                            throw IllegalArgumentException("HUD_METRICS")
                        }
                        widths[codePoint] = width
                    }
                    key.startsWith("ink.") -> {
                        val codePoint = key.substring(4).toInt()
                        val values =
                            properties.getProperty(key).split(',', ignoreCase = false, limit = 0)
                        if (
                            !Character.isValidCodePoint(codePoint) ||
                                values.size != 2 ||
                                ink.size >= 512
                        ) {
                            throw IllegalArgumentException("HUD_INK_METRICS")
                        }
                        val bounds =
                            FontMetrics.InkBounds(values[0].toDouble(), values[1].toDouble())
                        if (bounds.top < 0 || bounds.bottom > 192) {
                            throw IllegalArgumentException("HUD_INK_METRICS")
                        }
                        ink[codePoint] = bounds
                    }
                    key.startsWith("backdrop.") -> {
                        val width = properties.getProperty(key).toInt()
                        if (
                            !key.matches(Regex("backdrop\\.[a-z0-9_]+\\.[01]")) ||
                                width < 1 ||
                                width > 257 ||
                                backdropAdvances.size >= 512
                        ) {
                            throw IllegalArgumentException("HUD_BACKDROP_METRICS")
                        }
                        backdropAdvances[key.substring(9)] = width
                    }
                }
            }
            if (!widths.keys.containsAll(ink.keys)) {
                throw IllegalArgumentException("HUD_INK_METRICS")
            }
            if (properties.getProperty("lineHeight", "") != "10") {
                throw IllegalArgumentException("HUD_METRICS")
            }
            hudMetrics =
                MinecraftFontMetrics(
                    ContentFingerprint.hash("hud:$pin".toByteArray(StandardCharsets.UTF_8)).sha256,
                    widths,
                    10.0,
                    ink,
                )
        }
        hudBackdropAdvances = Collections.unmodifiableMap(HashMap(backdropAdvances))
    }

    fun hasEntry(path: String): Boolean = files.containsKey(path)

    fun profile(): MinecraftPackProfile = profile

    fun pin(): String = pin

    fun metrics(): MinecraftFontMetrics = metrics

    /** Frozen native bitmap advances, including transparent viewport gutters. */
    fun hudBackdropAdvance(key: String, half: Int): OptionalInt {
        if (half < 0 || half > 1) throw IllegalArgumentException("Invalid backdrop half")
        val width = hudBackdropAdvances["$key.$half"]
        return if (width == null) OptionalInt.empty() else OptionalInt.of(width)
    }

    fun hudMetrics(): MinecraftFontMetrics = hudMetrics

    fun glyphs(): Map<Int, Glyph> = glyphs

    fun manifestBytes(): ByteArray = manifest.clone()

    fun entries(): Map<String, ByteArray> =
        Collections.unmodifiableMap(
            TreeMap<String, ByteArray>().also { copied ->
                files.forEach { (path, bytes) -> copied[path] = bytes.clone() }
            }
        )

    companion object {
        @JvmStatic
        fun loadDefault(profile: MinecraftPackProfile): AssetBundle {
            val base = "/default-minecraft/${profile.id}/"
            val manifest = read(base + "manifest.json")
            val data = JsonParser.parseString(String(manifest, StandardCharsets.UTF_8)).asJsonObject
            val files = TreeMap<String, ByteArray>()
            data.getAsJsonObject("files").keySet().forEach { path ->
                files[path] = read(base + path)
            }
            return load(profile, manifest, files)
        }

        @JvmStatic
        fun load(
            profile: MinecraftPackProfile,
            manifest: ByteArray,
            entries: Map<String, ByteArray>,
        ): AssetBundle {
            if (manifest.size > 1024 * 1024) {
                throw IllegalArgumentException("ASSET_MANIFEST_LIMIT")
            }
            val data = JsonParser.parseString(String(manifest, StandardCharsets.UTF_8)).asJsonObject
            if (
                data.get("schemaVersion").asInt != 1 || data.get("profile").asString != profile.id
            ) {
                throw IllegalArgumentException("ASSET_PROFILE_MISMATCH")
            }
            val declared = data.getAsJsonObject("files")
            if (declared.keySet() != entries.keys) {
                throw IllegalArgumentException("ASSET_FILES_MISMATCH")
            }
            var total = manifest.size.toLong()
            val hashes = TreeMap<String, String>()
            for ((path, bytes) in entries) {
                if (
                    !path.startsWith("assets/dialogue/") ||
                        path.contains("..") ||
                        !path.matches(Regex("[a-z0-9_./-]+"))
                ) {
                    throw IllegalArgumentException("ASSET_PATH")
                }
                total += bytes.size
                if (total > 32 * 1024 * 1024) throw IllegalArgumentException("ASSET_LIMIT")
                val hash = ContentFingerprint.hash(bytes).sha256
                if (hash != declared.get(path).asString) {
                    throw IllegalArgumentException("ASSET_HASH_MISMATCH")
                }
                hashes[path] = hash
            }
            val glyphs = TreeMap<Int, Glyph>()
            val all = data.getAsJsonObject("glyphs")
            all.entrySet().forEach { (key, value) ->
                val glyph = value.asJsonObject
                val codePoint = key.toInt()
                val column = glyph.get("column").asInt
                val row = glyph.get("row").asInt
                val advance = glyph.get("advance").asInt
                if (
                    !Character.isValidCodePoint(codePoint) ||
                        column < 0 ||
                        column >= 16 ||
                        row < 0 ||
                        row >= 32 ||
                        advance < 1 ||
                        advance > 17
                ) {
                    throw IllegalArgumentException("GLYPH_METADATA")
                }
                glyphs[codePoint] = Glyph(column, row, advance)
            }
            val pin =
                ContentFingerprint.hash(
                        ContentFingerprint.canonicalBytes(
                            mapOf(
                                "profile" to profile.id,
                                "files" to hashes,
                                "glyphs" to Gson().fromJson(all, Map::class.java),
                            )
                        )
                    )
                    .sha256
            if (pin != data.get("pin").asString) {
                throw IllegalArgumentException("FONT_PIN_MISMATCH")
            }
            return AssetBundle(profile, manifest, entries, glyphs, pin)
        }

        private fun read(path: String): ByteArray {
            try {
                AssetBundle::class.java.getResourceAsStream(path).use { input ->
                    if (input == null) throw IllegalArgumentException("MISSING_ASSET: $path")
                    val result = input.readNBytes(32 * 1024 * 1024 + 1)
                    if (result.size > 32 * 1024 * 1024) {
                        throw IllegalArgumentException("ASSET_LIMIT")
                    }
                    return result
                }
            } catch (error: IOException) {
                throw UncheckedIOException(error)
            }
        }
    }
}
