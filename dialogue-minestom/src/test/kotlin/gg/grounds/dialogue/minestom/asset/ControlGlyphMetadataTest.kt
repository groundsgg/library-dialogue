package gg.grounds.dialogue.minestom.asset

import com.google.gson.Gson
import com.google.gson.JsonParser
import gg.grounds.dialogue.core.validation.ContentFingerprint
import gg.grounds.dialogue.presentation.FontMetrics
import java.util.TreeMap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ControlGlyphMetadataTest {
    private fun metadata(): String = buildString {
        appendLine("schemaVersion=1")
        for (profile in listOf("native", "hud")) for (cp in 0xe790..0xe793) {
            appendLine("$profile.advance.$cp=9")
            appendLine("$profile.ink.$cp=0,8")
        }
    }

    private fun withoutControlMetadata(bundle: AssetBundle): AssetBundle {
        val entries = TreeMap(bundle.entries())
        entries.remove("assets/dialogue/control_glyphs.properties")
        val data =
            JsonParser.parseString(String(bundle.manifestBytes(), Charsets.UTF_8)).asJsonObject
        val hashes = entries.mapValues { ContentFingerprint.hash(it.value).sha256 }.toSortedMap()
        data.add("files", Gson().toJsonTree(hashes))
        data.addProperty(
            "pin",
            ContentFingerprint.hash(
                    ContentFingerprint.canonicalBytes(
                        mapOf(
                            "profile" to bundle.profile().id,
                            "files" to hashes,
                            "glyphs" to
                                Gson().fromJson(data.getAsJsonObject("glyphs"), Map::class.java),
                        )
                    )
                )
                .sha256,
        )
        return AssetBundle.load(
            bundle.profile(),
            data.toString().toByteArray(Charsets.UTF_8),
            entries,
        )
    }

    @Test
    fun newBundlesMergeInputsWhileOldBundlesRetainTextCapability() {
        for (profile in
            listOf(MinecraftPackProfile.public262(), MinecraftPackProfile.grounds263())) {
            val bundle = AssetBundle.loadDefault(profile)
            assertEquals(200, bundle.glyphs().size)
            assertTrue(bundle.metrics().supportsControlGlyphs())
            assertTrue(bundle.hudMetrics().supportsControlGlyphs())
            for (cp in 0xe790..0xe793) {
                assertTrue(bundle.metrics().width(Character.toString(cp)) > 1)
                assertTrue(bundle.hudMetrics().inkBounds(Character.toString(cp)).bottom <= 9)
            }
            val old = withoutControlMetadata(bundle)
            assertFalse(old.metrics().supportsControlGlyphs())
            assertFalse(old.hudMetrics().supportsControlGlyphs())
            assertEquals(bundle.metrics().width("A"), old.metrics().width("A"))
            assertThrows(IllegalArgumentException::class.java) { old.metrics().width("\ue790") }
        }
    }

    @Test
    fun optionalMetadataRequiresCompleteBoundedFiniteMetrics() {
        assertTrue(ControlGlyphMetadata.parse(null).isEmpty())
        val valid = ControlGlyphMetadata.parse(metadata().toByteArray())
        assertEquals(9, valid.getValue("native").advances[0xe790])
        assertEquals(FontMetrics.InkBounds(0.0, 8.0), valid.getValue("hud").ink[0xe793])
        for (invalid in
            listOf(
                metadata().replace("native.advance.59280=9", ""),
                metadata().replace("0,8", "NaN,8"),
                metadata().replace("0,8", "0,13"),
                metadata().replace("schemaVersion=1", "schemaVersion=2"),
                metadata() + "native.advance.1=9",
                "x".repeat(4097),
            )) assertThrows(IllegalArgumentException::class.java) {
            ControlGlyphMetadata.parse(invalid.toByteArray())
        }
    }

    @Test
    fun legacyConstructorsNeverAdvertisePartialGlyphCoverage() {
        val widths = mapOf(0xe790 to 9)
        assertFalse(MinecraftFontMetrics("old", widths).supportsControlGlyphs())
        assertFalse(MinecraftFontMetrics("old", widths, 10.0).supportsControlGlyphs())
        assertFalse(MinecraftFontMetrics("old", widths, 10.0, emptyMap()).supportsControlGlyphs())
    }
}
