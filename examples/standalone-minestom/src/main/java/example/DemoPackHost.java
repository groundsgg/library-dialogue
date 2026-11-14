package example;

import com.sun.net.httpserver.HttpServer;
import gg.grounds.dialogue.minestom.asset.AssetBundle;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import net.kyori.adventure.resource.*;

/** Deterministic original sample pack, served only on loopback and sent once per connection. */
public final class DemoPackHost implements AutoCloseable {
  private final HttpServer server;
  private final byte[] bytes;
  private final String sha1, sha256;
  private final UUID id;

  public DemoPackHost(AssetBundle assets, Map<String, byte[]> extra, int port) throws Exception {
    var files = new TreeMap<String, byte[]>(assets.entries());
    for (var e : extra.entrySet()) {
      if (!e.getKey().startsWith("assets/npc_demo/")
          || e.getKey().contains("..")
          || !e.getKey().matches("[a-z0-9_./-]+")
          || files.putIfAbsent(e.getKey(), e.getValue()) != null)
        throw new IllegalArgumentException("Invalid demo asset");
    }
    var p = assets.profile();
    var metadata =
        "{\"pack\":{\"pack_format\":"
            + p.packMajor()
            + ",\"min_format\":["
            + p.packMajor()
            + ","
            + p.packMinor()
            + "],\"max_format\":["
            + p.packMajor()
            + ","
            + p.packMinor()
            + "],\"description\":\"Original Dialogue library demo\"}}";
    files.put("pack.mcmeta", metadata.getBytes(StandardCharsets.UTF_8));
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      for (var e : files.entrySet()) {
        var entry = new ZipEntry(e.getKey());
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(e.getValue());
        zip.closeEntry();
      }
    }
    bytes = out.toByteArray();
    sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    id = UUID.nameUUIDFromBytes(sha256.getBytes(StandardCharsets.US_ASCII));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.createContext(
        "/dialogue-demo.zip",
        exchange -> {
          try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
    server.start();
  }

  public URI uri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/dialogue-demo.zip");
  }

  public UUID packId() {
    return id;
  }

  public String sha256() {
    return sha256;
  }

  public byte[] bytes() {
    return bytes.clone();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public ResourcePackRequest request() {
    return ResourcePackRequest.resourcePackRequest()
        .packs(ResourcePackInfo.resourcePackInfo(id, uri(), sha1))
        .replace(false)
        .required(false)
        .build();
  }

  public void close() {
    server.stop(0);
  }
}
