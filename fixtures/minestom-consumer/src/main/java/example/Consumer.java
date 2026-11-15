package example;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.*;
import gg.grounds.dialogue.minestom.*;
import gg.grounds.dialogue.minestom.asset.*;
import gg.grounds.dialogue.minestom.channel.*;
import gg.grounds.dialogue.minestom.input.*;
import gg.grounds.dialogue.presentation.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minestom.server.*;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.*;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import net.minestom.server.network.player.*;

/**
 * Checks real published JARs, native host lifecycle, semantic fallback and an actual status socket.
 */
public final class Consumer {
  public static void main(String[] args) throws Exception {
    var server = MinecraftServer.init(new Auth.Offline());
    var world = MinecraftServer.getInstanceManager().createInstanceContainer();
    var assets = AssetBundle.loadDefault(MinecraftPackProfile.public262());
    var channels =
        new LocalOutputChannels(
            (p, c) -> {
              p.sendActionBar(c);
              return CompletableFuture.completedFuture(null);
            },
            false,
            (p, k) -> CompletableFuture.failedFuture(new UnsupportedOperationException()));
    var host =
        new MinestomDialogueHost(
            new MinestomDialogueOptions(
                new RuntimeOptions.Budgets(
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(30)),
                assets.profile(),
                assets,
                channels,
                UiLabelSource.defaults(),
                Duration.ofMillis(600)));
    Player player = null;
    try {
      server.start("127.0.0.1", 0);
      status(MinecraftServer.getServer().getPort());
      var chats = new AtomicInteger();
      var connection =
          new PlayerConnection() {
            public void sendPacket(SendablePacket packet) {
              if (SendablePacket.extractServerPacket(ConnectionState.PLAY, packet)
                  instanceof SystemChatPacket) chats.incrementAndGet();
            }

            public SocketAddress getRemoteAddress() {
              return new InetSocketAddress(0);
            }
          };
      player = new Player(connection, new GameProfile(UUID.randomUUID(), "consumer"));
      // The live tick loop owns scheduler processing; Player.join() also drains it and can race.
      player.setInstance(world, new Pos(0, 42, 0)).get(5, TimeUnit.SECONDS);
      var texts = new TextCatalog(Map.of("en", Map.of("line", "Hello native consumer")));
      var definition =
          DialogueBuilder.dialogue("consumer:minestom")
              .node("start", new Message("guide", "line", null, List.of(), "end"))
              .node("end", new End("completed"))
              .build();
      var content =
          new DialogueValidator()
              .validate(
                  definition,
                  texts,
                  new HostCatalog(
                      "public", Set.of("guide"), Map.of(), Map.of(), Set.of(), Set.of(), false),
                  ValidationLimits.defaults())
              .validated();
      if (content == null) throw new AssertionError("Validation failed");
      var ticket =
          host.begin(
              new MinestomDialogueRequest(
                  "consumer",
                  player,
                  1,
                  content,
                  new DialogueServices(
                      "public",
                      Map.of(),
                      Map.of(),
                      null,
                      TextResolver.catalogue(texts, "en"),
                      Set.of()),
                  Map.of(),
                  new LocaleContext("en", 0, "en"),
                  new PresentationPreferences("hud", Duration.ZERO, false, Map.of()),
                  Map.of(),
                  null,
                  InputProfile.defaults(),
                  0));
      var start = ticket.result().toCompletableFuture().get(5, TimeUnit.SECONDS);
      if (!(start instanceof DialogueStartResult.Started ready)) throw new AssertionError(start);
      if (!ticket.status().effectiveSurface().equals("chat") || chats.get() != 1)
        throw new AssertionError("Packless native host did not emit one semantic section");
      var handle = ready.handle();
      var view = handle.snapshot();
      handle
          .submit(
              new DialogueInput.Confirm(
                  new DialogueInput.InputStamp(view.sessionId(), view.renderRevision(), 1), null))
          .toCompletableFuture()
          .get(5, TimeUnit.SECONDS);
      if (handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).reason()
          != DialogueEndReason.COMPLETED) throw new AssertionError("Close failed");
      host.closeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
      for (var entry :
          Map.of(
                  "dialogue-core",
                  DialogueRuntime.class,
                  "dialogue-presentation",
                  DialoguePresenter.class,
                  "dialogue-minestom",
                  MinestomDialogueHost.class)
              .entrySet()) {
        var file =
            Path.of(entry.getValue().getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!file.getFileName().toString().endsWith(".jar"))
          throw new AssertionError("Expected Maven JAR");
        var hash =
            HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        System.out.println("CONSUMER_JAR " + entry.getKey() + " sha256=" + hash);
      }
      System.out.println("CONSUMER_OK minestom");
    } finally {
      host.closeAsync().toCompletableFuture().get(35, TimeUnit.SECONDS);
      if (player != null) player.remove();
      MinecraftServer.stopCleanly();
    }
  }

  private static void writeVarInt(DataOutputStream out, int value) throws IOException {
    do {
      int part = value & 127;
      value >>>= 7;
      out.writeByte(part | (value == 0 ? 0 : 128));
    } while (value != 0);
  }

  private static int readVarInt(DataInputStream in) throws IOException {
    int result = 0;
    for (int i = 0; i < 5; i++) {
      int b = in.readUnsignedByte();
      result |= (b & 127) << (7 * i);
      if (b < 128) return result;
    }
    throw new IOException("Invalid varint");
  }

  private static void packet(DataOutputStream out, byte[] payload) throws IOException {
    writeVarInt(out, payload.length);
    out.write(payload);
    out.flush();
  }

  private static void status(int port) throws Exception {
    try (var socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(3000);
      var bytes = new ByteArrayOutputStream();
      var handshake = new DataOutputStream(bytes);
      writeVarInt(handshake, 0);
      writeVarInt(handshake, MinecraftServer.PROTOCOL_VERSION);
      byte[] name = "localhost".getBytes(StandardCharsets.UTF_8);
      writeVarInt(handshake, name.length);
      handshake.write(name);
      handshake.writeShort(port);
      writeVarInt(handshake, 1);
      var out = new DataOutputStream(socket.getOutputStream());
      packet(out, bytes.toByteArray());
      packet(out, new byte[] {0});
      var in = new DataInputStream(socket.getInputStream());
      int length = readVarInt(in);
      if (length < 1 || length > 1_048_576) throw new AssertionError("Invalid status frame");
      var frame = new DataInputStream(new ByteArrayInputStream(in.readNBytes(length)));
      if (readVarInt(frame) != 0) throw new AssertionError("Status ID");
      var json =
          new String(frame.readNBytes(readVarInt(frame)), StandardCharsets.UTF_8).replace(" ", "");
      if (!json.contains("\"protocol\":" + MinecraftServer.PROTOCOL_VERSION))
        throw new AssertionError(json);
    }
  }
}
