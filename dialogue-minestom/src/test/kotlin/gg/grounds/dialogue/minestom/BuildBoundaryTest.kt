package gg.grounds.dialogue.minestom

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.jar.JarFile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

internal class BuildBoundaryTest {
    @Test
    fun noHostFontsOrPrivateDependencyInAdapterArtifact() {
        JarFile(Path.of(System.getProperty("dialogue.adapterJar")).toFile()).use { jar ->
            assertNotNull(jar.getEntry("META-INF/LICENSE"))
            assertNotNull(
                jar.getEntry(
                    "default-minecraft/grounds-26.3/assets/dialogue/font-source/font-license.txt"
                )
            )
            val forbidden = listOf("java/awt/", "gg/grounds/scene/", "gg/grounds/npc/", "probe/")
            jar.stream()
                .filter { it.name.endsWith(".class") }
                .forEach { entry ->
                    val constants =
                        jar.getInputStream(entry).use {
                            it.readAllBytes().toString(StandardCharsets.ISO_8859_1)
                        }
                    for (reference in forbidden) {
                        assertFalse(
                            constants.contains(reference),
                            entry.name + " leaks " + reference,
                        )
                    }
                }
        }
    }
}
