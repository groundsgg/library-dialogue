package gg.grounds.dialogue.minestom;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;

class BuildBoundaryTest {
  @Test
  void noHostFontsOrPrivateDependencyInAdapterArtifact() throws Exception {
    try (var jar = new JarFile(Path.of(System.getProperty("dialogue.adapterJar")).toFile())) {
      assertNotNull(jar.getEntry("META-INF/LICENSE"));
      assertNotNull(
          jar.getEntry(
              "default-minecraft/grounds-26.3/assets/dialogue/font-source/font-license.txt"));
      for (var e : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
        var bytes = jar.getInputStream(e).readAllBytes();
        var constants = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String forbidden :
            new String[] {"java/awt/", "gg/grounds/scene/", "gg/grounds/npc/", "probe/"})
          assertFalse(constants.contains(forbidden), e.getName() + " leaks " + forbidden);
      }
    }
  }
}
