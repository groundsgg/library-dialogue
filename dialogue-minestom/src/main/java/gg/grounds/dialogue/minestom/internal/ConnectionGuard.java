package gg.grounds.dialogue.minestom.internal;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

/** The host passes concrete local connection objects, never a proxy-wide Player lookup. */
public final class ConnectionGuard {
  private final Player player;
  private final Instance instance;
  private final long generation;
  private final AtomicBoolean valid = new AtomicBoolean(true);

  public ConnectionGuard(Player player, long generation, Instance instance) {
    this.player = Objects.requireNonNull(player);
    this.instance = Objects.requireNonNull(instance);
    if (generation < 0) throw new IllegalArgumentException("generation");
    this.generation = generation;
  }

  public boolean isCurrent() {
    return valid.get() && hasCurrentConnection();
  }

  /**
   * Cleanup may repair current authoritative state after invalidation, within the same instance.
   */
  public boolean hasCurrentConnection() {
    return player.isOnline() && player.getInstance() == instance;
  }

  public boolean matches(Player player, long generation) {
    return this.player == player && this.generation == generation && isCurrent();
  }

  public void invalidate() {
    valid.set(false);
  }
}
