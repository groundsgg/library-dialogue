package gg.grounds.dialogue.minestom.asset;

import java.util.*;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.minestom.server.entity.Player;

/** A pack must be registered before sending it; status packets cannot choose their own manifest. */
public final class PackStateTracker {
  private record Expected(long generation, UUID id, String pin, boolean ready) {}

  private final Map<Player, Expected> current = new WeakHashMap<>();

  public synchronized void expect(Player player, long generation, UUID id, String manifestPin) {
    Objects.requireNonNull(player);
    Objects.requireNonNull(id);
    Objects.requireNonNull(manifestPin);
    if (generation < 0) throw new IllegalArgumentException("generation");
    var old = current.get(player);
    if (old != null && generation < old.generation())
      throw new IllegalArgumentException("STALE_PACK_GENERATION");
    if (old != null && old.id().equals(id)) {
      if (old.generation() != generation || !old.pin().equals(manifestPin))
        throw new IllegalArgumentException("PACK_UUID_REUSED");
      return;
    }
    current.put(player, new Expected(generation, id, manifestPin, false));
  }

  public synchronized void observe(
      Player player, long generation, UUID id, String pin, ResourcePackStatus status) {
    var e = current.get(player);
    if (e == null || e.generation() != generation || !e.id().equals(id) || !e.pin().equals(pin))
      return;
    current.put(
        player,
        new Expected(generation, id, pin, status == ResourcePackStatus.SUCCESSFULLY_LOADED));
  }

  public synchronized void observe(Player player, UUID id, ResourcePackStatus status) {
    var e = current.get(player);
    if (e != null) observe(player, e.generation(), id, e.pin(), status);
  }

  public synchronized boolean isReady(Player player, long generation, String pin) {
    var e = current.get(player);
    return player.isOnline()
        && e != null
        && e.generation() == generation
        && e.pin().equals(pin)
        && e.ready();
  }

  public synchronized void forget(Player player) {
    current.remove(player);
  }

  public synchronized void clear() {
    current.clear();
  }
}
