package gg.grounds.dialogue.minestom.input;

import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongSupplier;
import net.minestom.server.entity.Player;

/** Opaque capabilities scoped to one published semantic section and concrete connection. */
public final class ChatInputTokens {
  private record Issued(TargetSnapshot.Target target, long expires) {}

  private final Player player;
  private final long generation;
  private final SurfaceInputTargets targets;
  private final LongSupplier clock;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Issued> tokens = new HashMap<>();

  public ChatInputTokens(
      Player player, long generation, SurfaceInputTargets targets, LongSupplier clock) {
    this.player = Objects.requireNonNull(player);
    this.generation = generation;
    this.targets = Objects.requireNonNull(targets);
    this.clock = Objects.requireNonNull(clock);
  }

  public String issue(String choiceId, TargetSnapshot snapshot) {
    long now = clock.getAsLong();
    synchronized (this) {
      return issueAt(choiceId, snapshot, now);
    }
  }

  private String issueAt(String choiceId, TargetSnapshot snapshot, long now) {
    if (choiceId != null && !snapshot.choices().containsKey(choiceId))
      throw new IllegalArgumentException("UNISSUED_CHOICE");
    tokens.values().removeIf(t -> t.expires() < now);
    if (tokens.size() >= 64) throw new IllegalStateException("CHAT_TOKEN_LIMIT");
    var bytes = new byte[16];
    String token;
    do {
      random.nextBytes(bytes);
      token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    } while (tokens.containsKey(token));
    tokens.put(
        token,
        new Issued(
            new TargetSnapshot.Target(
                snapshot.sessionId(),
                snapshot.renderRevision(),
                snapshot.nodeVisitId(),
                snapshot.surfaceGeneration(),
                choiceId),
            now + 30_000_000_000L));
    return token;
  }

  public Optional<TargetSnapshot.Target> resolve(Player player, long generation, String token) {
    if (this.player != player || this.generation != generation || !player.isOnline())
      return Optional.empty();
    long now = clock.getAsLong();
    synchronized (this) {
      return resolveAt(token, now);
    }
  }

  private Optional<TargetSnapshot.Target> resolveAt(String token, long now) {
    var issued = tokens.get(token);
    var current = targets.current();
    if (issued == null || current == null || issued.expires() < now) return Optional.empty();
    var t = issued.target();
    if (!t.sessionId().equals(current.sessionId())
        || t.nodeVisitId() != current.nodeVisitId()
        || t.renderRevision() != current.renderRevision()
        || t.surfaceGeneration() != current.surfaceGeneration()
        || (t.choiceId() != null && !current.choices().containsKey(t.choiceId())))
      return Optional.empty();
    return Optional.of(t);
  }

  /**
   * Only identical semantic sections may reuse tokens while the core advances its reveal revision.
   */
  public synchronized void rebase(TargetSnapshot snapshot) {
    tokens.replaceAll(
        (k, v) ->
            new Issued(
                new TargetSnapshot.Target(
                    snapshot.sessionId(),
                    snapshot.renderRevision(),
                    snapshot.nodeVisitId(),
                    snapshot.surfaceGeneration(),
                    v.target().choiceId()),
                v.expires()));
  }

  public synchronized void clear() {
    tokens.clear();
  }
}
