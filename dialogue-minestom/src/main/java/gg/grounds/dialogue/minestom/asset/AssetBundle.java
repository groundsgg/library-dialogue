package gg.grounds.dialogue.minestom.asset;

import com.google.gson.*;
import gg.grounds.dialogue.core.validation.ContentFingerprint;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable verified pack contribution. The host owns pack metadata, composition and hosting. */
public final class AssetBundle {
  public record Glyph(int column, int row, int advance) {}

  private final MinecraftPackProfile profile;
  private final byte[] manifest;
  private final Map<String, byte[]> files;
  private final Map<Integer, Glyph> glyphs;
  private final MinecraftFontMetrics metrics, hudMetrics;
  private final String pin;

  private AssetBundle(
      MinecraftPackProfile profile,
      byte[] manifest,
      Map<String, byte[]> files,
      Map<Integer, Glyph> glyphs,
      String pin) {
    this.profile = profile;
    this.manifest = manifest.clone();
    var copied = new TreeMap<String, byte[]>();
    files.forEach((k, v) -> copied.put(k, v.clone()));
    this.files = Collections.unmodifiableMap(copied);
    this.glyphs = Map.copyOf(glyphs);
    this.pin = pin;
    var advances = new HashMap<Integer, Integer>();
    glyphs.forEach((k, v) -> advances.put(k, v.advance()));
    advances.put(32, 4);
    advances.put(160, 4);
    metrics = new MinecraftFontMetrics(pin, advances);
    var hud = files.get("assets/dialogue/hud_metrics.properties");
    if (hud == null) hudMetrics = metrics;
    else {
      if (hud.length > 65536) throw new IllegalArgumentException("HUD_METRICS_LIMIT");
      var properties = new Properties();
      try {
        properties.load(new ByteArrayInputStream(hud));
      } catch (IOException e) {
        throw new IllegalArgumentException("HUD_METRICS", e);
      }
      var widths = new HashMap<Integer, Integer>();
      for (String k : properties.stringPropertyNames())
        if (k.startsWith("glyph.")) {
          int cp = Integer.parseInt(k.substring(6)),
              width = Integer.parseInt(properties.getProperty(k));
          if (!Character.isValidCodePoint(cp) || width < 1 || width > 17 || widths.size() >= 512)
            throw new IllegalArgumentException("HUD_METRICS");
          widths.put(cp, width);
        }
      if (!properties.getProperty("lineHeight", "").equals("10"))
        throw new IllegalArgumentException("HUD_METRICS");
      hudMetrics =
          new MinecraftFontMetrics(
              ContentFingerprint.hash(("hud:" + pin).getBytes(StandardCharsets.UTF_8)).sha256(),
              widths,
              10);
    }
  }

  public static AssetBundle loadDefault(MinecraftPackProfile profile) {
    String base = "/default-minecraft/" + profile.id() + "/";
    var manifest = read(base + "manifest.json");
    var data =
        JsonParser.parseString(new String(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
    var files = new TreeMap<String, byte[]>();
    data.getAsJsonObject("files").keySet().forEach(k -> files.put(k, read(base + k)));
    return load(profile, manifest, files);
  }

  public static AssetBundle load(
      MinecraftPackProfile profile, byte[] manifest, Map<String, byte[]> entries) {
    Objects.requireNonNull(profile);
    if (manifest.length > 1024 * 1024) throw new IllegalArgumentException("ASSET_MANIFEST_LIMIT");
    var data =
        JsonParser.parseString(new String(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
    if (data.get("schemaVersion").getAsInt() != 1
        || !data.get("profile").getAsString().equals(profile.id()))
      throw new IllegalArgumentException("ASSET_PROFILE_MISMATCH");
    var declared = data.getAsJsonObject("files");
    if (!declared.keySet().equals(entries.keySet()))
      throw new IllegalArgumentException("ASSET_FILES_MISMATCH");
    long total = manifest.length;
    var hashes = new TreeMap<String, String>();
    for (var e : entries.entrySet()) {
      String k = e.getKey();
      if (!k.startsWith("assets/dialogue/") || k.contains("..") || !k.matches("[a-z0-9_./-]+"))
        throw new IllegalArgumentException("ASSET_PATH");
      total += e.getValue().length;
      if (total > 32 * 1024 * 1024) throw new IllegalArgumentException("ASSET_LIMIT");
      String hash = ContentFingerprint.hash(e.getValue()).sha256();
      if (!hash.equals(declared.get(k).getAsString()))
        throw new IllegalArgumentException("ASSET_HASH_MISMATCH");
      hashes.put(k, hash);
    }
    var glyphs = new TreeMap<Integer, Glyph>();
    var all = data.getAsJsonObject("glyphs");
    all.entrySet()
        .forEach(
            e -> {
              var g = e.getValue().getAsJsonObject();
              int cp = Integer.parseInt(e.getKey()),
                  x = g.get("column").getAsInt(),
                  y = g.get("row").getAsInt(),
                  a = g.get("advance").getAsInt();
              if (!Character.isValidCodePoint(cp)
                  || x < 0
                  || x >= 16
                  || y < 0
                  || y >= 32
                  || a < 1
                  || a > 17) throw new IllegalArgumentException("GLYPH_METADATA");
              glyphs.put(cp, new Glyph(x, y, a));
            });
    String pin =
        ContentFingerprint.hash(
                ContentFingerprint.canonicalBytes(
                    Map.of(
                        "profile",
                        profile.id(),
                        "files",
                        hashes,
                        "glyphs",
                        new Gson().fromJson(all, Map.class))))
            .sha256();
    if (!pin.equals(data.get("pin").getAsString()))
      throw new IllegalArgumentException("FONT_PIN_MISMATCH");
    return new AssetBundle(profile, manifest, entries, glyphs, pin);
  }

  private static byte[] read(String path) {
    try (var in = AssetBundle.class.getResourceAsStream(path)) {
      if (in == null) throw new IllegalArgumentException("MISSING_ASSET: " + path);
      byte[] result = in.readNBytes(32 * 1024 * 1024 + 1);
      if (result.length > 32 * 1024 * 1024) throw new IllegalArgumentException("ASSET_LIMIT");
      return result;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public boolean hasEntry(String path) {
    return files.containsKey(path);
  }

  public MinecraftPackProfile profile() {
    return profile;
  }

  public String pin() {
    return pin;
  }

  public MinecraftFontMetrics metrics() {
    return metrics;
  }

  public MinecraftFontMetrics hudMetrics() {
    return hudMetrics;
  }

  public Map<Integer, Glyph> glyphs() {
    return glyphs;
  }

  public byte[] manifestBytes() {
    return manifest.clone();
  }

  public Map<String, byte[]> entries() {
    var copied = new TreeMap<String, byte[]>();
    files.forEach((k, v) -> copied.put(k, v.clone()));
    return Collections.unmodifiableMap(copied);
  }
}
