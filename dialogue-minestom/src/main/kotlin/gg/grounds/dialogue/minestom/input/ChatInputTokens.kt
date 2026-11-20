package gg.grounds.dialogue.minestom.input

import java.security.SecureRandom
import java.util.Base64
import java.util.HashMap
import java.util.Optional
import java.util.function.LongSupplier
import net.minestom.server.entity.Player

/** Opaque capabilities scoped to one published semantic section and concrete connection. */
class ChatInputTokens(
    private val player: Player,
    private val generation: Long,
    private val targets: SurfaceInputTargets,
    private val clock: LongSupplier,
) {
    @JvmRecord private data class Issued(val target: TargetSnapshot.Target, val expires: Long)

    private val random = SecureRandom()
    private val tokens = HashMap<String, Issued>()

    fun issue(choiceId: String?, snapshot: TargetSnapshot): String {
        val now = clock.asLong
        return synchronized(this) { issueAt(choiceId, snapshot, now) }
    }

    private fun issueAt(choiceId: String?, snapshot: TargetSnapshot, now: Long): String {
        if (choiceId != null && !snapshot.choices().containsKey(choiceId)) {
            throw IllegalArgumentException("UNISSUED_CHOICE")
        }
        tokens.values.removeIf { it.expires < now }
        if (tokens.size >= 64) throw IllegalStateException("CHAT_TOKEN_LIMIT")
        val bytes = ByteArray(16)
        var token: String
        do {
            random.nextBytes(bytes)
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } while (tokens.containsKey(token))
        tokens[token] =
            Issued(
                TargetSnapshot.Target(
                    snapshot.sessionId(),
                    snapshot.renderRevision(),
                    snapshot.nodeVisitId(),
                    snapshot.surfaceGeneration(),
                    choiceId,
                ),
                now + 30_000_000_000L,
            )
        return token
    }

    fun resolve(player: Player, generation: Long, token: String): Optional<TargetSnapshot.Target> {
        if (this.player !== player || this.generation != generation || !player.isOnline) {
            return Optional.empty()
        }
        val now = clock.asLong
        return synchronized(this) { resolveAt(token, now) }
    }

    private fun resolveAt(token: String, now: Long): Optional<TargetSnapshot.Target> {
        val issued = tokens[token]
        val current = targets.current()
        if (issued == null || current == null || issued.expires < now) return Optional.empty()
        val target = issued.target
        if (
            target.sessionId != current.sessionId() ||
                target.nodeVisitId != current.nodeVisitId() ||
                target.renderRevision != current.renderRevision() ||
                target.surfaceGeneration != current.surfaceGeneration() ||
                (target.choiceId != null && !current.choices().containsKey(target.choiceId))
        ) {
            return Optional.empty()
        }
        return Optional.of(target)
    }

    /**
     * Only identical semantic sections may reuse tokens while the core advances its reveal
     * revision.
     */
    @Synchronized
    fun rebase(snapshot: TargetSnapshot) {
        tokens.replaceAll { _, issued ->
            Issued(
                TargetSnapshot.Target(
                    snapshot.sessionId(),
                    snapshot.renderRevision(),
                    snapshot.nodeVisitId(),
                    snapshot.surfaceGeneration(),
                    issued.target.choiceId,
                ),
                issued.expires,
            )
        }
    }

    @Synchronized
    fun clear() {
        tokens.clear()
    }
}
