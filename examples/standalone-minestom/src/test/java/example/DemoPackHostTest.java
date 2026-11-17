package example;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.minestom.asset.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

class DemoPackHostTest {
  @Test
  void packIsDeterministicAndAdvertisedUrlDownloads() throws Exception {
    var assets = AssetBundle.loadDefault(MinecraftPackProfile.public262());
    try (var first = new DemoPackHost(assets, Map.of(), 0);
        var second = new DemoPackHost(assets, Map.of(), 0)) {
      assertArrayEquals(first.bytes(), second.bytes());
      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(first.uri()).GET().build(),
                  HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, response.statusCode());
      assertArrayEquals(first.bytes(), response.body());
      assertEquals(first.packId(), second.packId());
      var names = new HashSet<String>();
      try (var zip = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
        for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
          names.add(entry.getName());
          assertFalse(entry.getName().startsWith("assets/minecraft/"));
        }
      }
      assertTrue(names.contains("pack.mcmeta"));
      assertTrue(names.contains("assets/dialogue/font/mask.json"));
    }
  }

  @Test
  void failureProbeReturnsReal404WithoutChangingTheHealthyPack() throws Exception {
    var assets = AssetBundle.loadDefault(MinecraftPackProfile.public262());
    try (var host = new DemoPackHost(assets, Map.of(), 0)) {
      var id = UUID.randomUUID();
      var request = host.request(id, true);
      var info = request.packs().getFirst();
      assertEquals(id, info.id());
      assertFalse(request.required());
      assertNotEquals(host.request().packs().getFirst().hash(), info.hash());
      assertNotEquals(host.uri(), info.uri());
      var client = HttpClient.newHttpClient();
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(info.uri()).GET().build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode());
      var restored = host.request(UUID.randomUUID(), false).packs().getFirst();
      assertEquals(host.uri(), restored.uri());
      assertEquals(host.request().packs().getFirst().hash(), restored.hash());
      var healthy =
          client.send(
              HttpRequest.newBuilder(restored.uri()).GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, healthy.statusCode());
      assertArrayEquals(host.bytes(), healthy.body());
    }
  }

  @Test
  void demoContentIsValidatedForBothLanguages() {
    var content = DialogueDemo.content();
    assertNotNull(content);
    assertEquals(Set.of("de", "en"), Set.copyOf(content.definition().locales().supported()));
  }
}
