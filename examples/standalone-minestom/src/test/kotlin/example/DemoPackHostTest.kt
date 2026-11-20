package example

import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.minestom.asset.MinecraftPackProfile
import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemoPackHostTest {
    @Test
    fun packIsDeterministicAndAdvertisedUrlDownloads() {
        val assets = AssetBundle.loadDefault(MinecraftPackProfile.public262())
        DemoPackHost(assets, emptyMap(), 0).use { first ->
            DemoPackHost(assets, emptyMap(), 0).use { second ->
                assertArrayEquals(first.bytes(), second.bytes())
                val response =
                    HttpClient.newHttpClient()
                        .send(
                            HttpRequest.newBuilder(first.uri()).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray(),
                        )
                assertEquals(200, response.statusCode())
                assertArrayEquals(first.bytes(), response.body())
                assertEquals(first.packId(), second.packId())
                val names = HashSet<String>()
                ZipInputStream(ByteArrayInputStream(response.body())).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        names += entry.name
                        assertFalse(entry.name.startsWith("assets/minecraft/"))
                    }
                }
                assertTrue("pack.mcmeta" in names)
                assertTrue("assets/dialogue/font/mask.json" in names)
            }
        }
    }

    @Test
    fun failureProbeReturnsReal404WithoutChangingTheHealthyPack() {
        val assets = AssetBundle.loadDefault(MinecraftPackProfile.public262())
        DemoPackHost(assets, emptyMap(), 0).use { host ->
            val id = UUID.randomUUID()
            val request = host.request(id, true)
            val info = request.packs().first()
            assertEquals(id, info.id())
            assertFalse(request.required())
            assertNotEquals(host.request().packs().first().hash(), info.hash())
            assertNotEquals(host.uri(), info.uri())
            val client = HttpClient.newHttpClient()
            assertEquals(
                404,
                client
                    .send(
                        HttpRequest.newBuilder(info.uri()).GET().build(),
                        HttpResponse.BodyHandlers.discarding(),
                    )
                    .statusCode(),
            )
            val restored = host.request(UUID.randomUUID(), false).packs().first()
            assertEquals(host.uri(), restored.uri())
            assertEquals(host.request().packs().first().hash(), restored.hash())
            val healthy =
                client.send(
                    HttpRequest.newBuilder(restored.uri()).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
            assertEquals(200, healthy.statusCode())
            assertArrayEquals(host.bytes(), healthy.body())
        }
    }

    @Test
    fun demoContentIsValidatedForBothLanguages() {
        val content = DialogueDemo.content()
        assertNotNull(content)
        assertEquals(setOf("de", "en"), content.definition().locales().supported().toSet())
    }
}
