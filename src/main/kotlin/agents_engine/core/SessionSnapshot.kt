package agents_engine.core

import agents_engine.model.LlmMessage
import agents_engine.model.TokenUsage

/**
 * #2416 / #2386 — snapshot/resume (v1 spike).
 *
 * The design hinge: an agent's resumable state is its **message history +
 * loop counters** (LLM turns are stateless — each call re-sends the whole
 * message list). So "resume" means re-enter the agentic loop seeded with a
 * saved [SessionSnapshot], NOT suspend/serialise a coroutine. `executeAgentic`
 * checkpoints a snapshot at each turn boundary (via its `onTurnCheckpoint`
 * hook) and can be seeded with one (`resumeFrom`).
 */

/**
 * The resumable state of one agent invocation, captured at a turn boundary
 * (after tools complete — never mid-tool). Serialises through plain JSON; no
 * new serializer dependency.
 *
 * `manifestHash` is enforced by the restore path (#2754 in 0.6.4): when a
 * non-null snapshot.manifestHash disagrees with the current agent's
 * manifestHash, resume throws [SnapshotManifestMismatchException] unless the
 * caller passes `allowManifestMismatch = true`. Null snapshot.manifestHash is
 * treated as "no manifest at the time of snapshot" (e.g., pre-0.6.4 file) and
 * is allowed.
 */
data class SessionSnapshot(
    val messages: List<LlmMessage>,
    val turns: Int,
    val toolCalls: Int,
    val toolCallLimit: Int,
    val tokensUsed: TokenUsage?,
    val memory: Map<String, String>,
    val requestId: String,
    val sessionId: String?,
    val manifestHash: String?,
    /**
     * #2488 — when an interrupted tool call is pending resume, this carries
     * the call_id whose result the runtime will synthesise from
     * `invokeSuspendResuming(..., resumeWith = ...)`. Null on a normal
     * turn-boundary snapshot. Serialised through [SnapshotJson] so a
     * snapshot persisted via [FileSnapshotStore] across a process restart
     * still resumes deterministically.
     */
    val pendingInterruptCallId: String? = null,
)
