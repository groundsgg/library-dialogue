package example

import com.sun.net.httpserver.HttpServer
import gg.grounds.dialogue.minestom.asset.AssetBundle
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.TreeMap
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest

/** Deterministic original sample pack, served only on loopback and sent once per connection. */
class DemoPackHost(assets: AssetBundle, extra: Map<String, ByteArray>, port: Int) : AutoCloseable {
    private val server: HttpServer
    private val archiveBytes: ByteArray
    private val sha1: String
    private val sha256Value: String
    private val id: UUID

    init {
        val files = TreeMap<String, ByteArray>(assets.entries())
        for ((path, contents) in extra) {
            if (
                !path.startsWith("assets/npc_demo/") ||
                    ".." in path ||
                    !path.matches(Regex("[a-z0-9_./-]+")) ||
                    files.putIfAbsent(path, contents) != null
            ) {
                throw IllegalArgumentException("Invalid demo asset")
            }
        }
        val profile = assets.profile()
        val metadata =
            """{"pack":{"pack_format":${profile.packMajor},"min_format":[${profile.packMajor},${profile.packMinor}],"max_format":[${profile.packMajor},${profile.packMinor}],"description":"Original Dialogue library demo"}}"""
        files["pack.mcmeta"] = metadata.toByteArray(StandardCharsets.UTF_8)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((path, contents) in files) {
                val entry = ZipEntry(path)
                entry.time = 0
                zip.putNextEntry(entry)
                zip.write(contents)
                zip.closeEntry()
            }
        }
        archiveBytes = output.toByteArray()
        sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(archiveBytes))
        sha256Value =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archiveBytes))
        id = UUID.nameUUIDFromBytes(sha256Value.toByteArray(StandardCharsets.US_ASCII))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/dialogue-demo.zip") { exchange ->
            exchange.use {
                it.responseHeaders.set("Content-Type", "application/zip")
                it.sendResponseHeaders(200, archiveBytes.size.toLong())
                it.responseBody.write(archiveBytes)
            }
        }
        server.start()
    }

    fun uri(): URI = URI.create("http://127.0.0.1:${server.address.port}/dialogue-demo.zip")

    fun packId(): UUID = id

    fun sha256(): String = sha256Value

    fun bytes(): ByteArray = archiveBytes.clone()

    fun port(): Int = server.address.port

    fun request(): ResourcePackRequest = request(id, failDownload = false)

    /** Explicit client-test request; the failure URL is deliberately not served. */
    fun request(requestId: UUID, failDownload: Boolean): ResourcePackRequest =
        ResourcePackRequest.resourcePackRequest()
            .packs(
                ResourcePackInfo.resourcePackInfo(
                    requestId,
                    if (failDownload) uri().resolve("/missing-demo-pack.zip") else uri(),
                    if (failDownload) "0".repeat(40) else sha1,
                )
            )
            .replace(false)
            .required(false)
            .build()

    override fun close() {
        server.stop(0)
    }
}
