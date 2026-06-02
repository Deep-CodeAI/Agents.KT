package agents_engine.core

/**
 * `agents_engine/core/Interrupt.kt` — HITL interrupt primitive (#2488).
 *
 * The use case: a tool inside an agentic loop needs typed external input
 * — most often human input — to continue. Examples: `ask_human` for
 * clarification, `request_approval` for a sensitive action, `solve_captcha`
 * passed to an operator. The tool throws via [interrupt]; the agentic
 * loop captures a [SessionSnapshot] at the in-flight boundary and surfaces
 * [AgentInterruptException] to the caller. The caller persists the
 * snapshot, asks the human, then resumes via
 * `invokeSuspendResuming(input, resumeFrom = snapshot, resumeWith = reply)`.
 *
 * The runtime synthesises the interrupted tool call's result from
 * `resumeWith` (via [agents_engine.generation.toLlmInput] so typed
 * `@Generable` replies render as JSON) and continues the loop. From the
 * model's perspective the round-trip is invisible — it sees the assistant
 * turn it emitted, then the tool result, then the next turn.
 *
 * **Composition with the existing snapshot machinery (#2752 trust patch):**
 * - Snapshot persistence: any [SnapshotStore], including [FileSnapshotStore]
 *   (filename-safe per #2753).
 * - Manifest-hash restore guard (#2754) applies — a snapshot taken under
 *   one tool/permission set will refuse to resume against an agent whose
 *   manifest has since changed, unless the caller opts in via
 *   `allowManifestMismatch = true`.
 * - Memory restore is per-agent (#2755) — shared-bank topologies safe.
 * - Composition operators (Pipeline / Branch / Loop / Parallel) propagate
 *   [AgentInterruptException] unchanged; only the leaf agent that called
 *   `interrupt(...)` owns the snapshot.
 *
 * **v1 constraint:** one outstanding interrupted call per resume. The
 * runtime synthesises the tool result for the single call carried by
 * `snapshot.pendingInterruptCallId`. If the model emitted multiple tool
 * calls in the same assistant turn and one of them interrupts, the
 * remaining calls in that turn are NOT executed — the throw bubbles out
 * before they're reached. On resume the model sees only the synthesised
 * result for the interrupted call; it can re-issue the others on its
 * next turn if it wants.
 *
 * Pairs with #2487 (HITL epic).
 */

/**
 * Pause this agentic loop and surface [payload] to the caller.
 *
 * Called from inside a tool executor. The current tool's result is treated
 * as pending; the agentic loop captures a [SessionSnapshot] and throws
 * [AgentInterruptException] carrying that snapshot plus this [payload].
 *
 * On resume via `invokeSuspendResuming(..., resumeFrom = snapshot,
 * resumeWith = reply)`, the runtime synthesises a tool result message
 * for the interrupted call (using [agents_engine.generation.toLlmInput]
 * so typed `@Generable` replies render as JSON) and continues the loop.
 * This function never returns on the suspending side — the resumed loop
 * starts fresh from the snapshot.
 *
 * Marked [Nothing] return so the type-checker treats the call site as
 * non-returning. Useful when the tool is `tool<Args, Reply>` and the
 * compiler would otherwise complain about a missing return.
 *
 * @param payload arbitrary serialisable value surfaced on the exception.
 *   Typically a question or a context object for the human.
 */
fun interrupt(payload: Any?): Nothing =
    throw PendingInterruptSignal(payload)

/**
 * Internal marker thrown by [interrupt]. Caught by the agentic loop, which
 * builds the user-facing [AgentInterruptException] from the in-flight
 * state (snapshot + call_id) and rethrows.
 *
 * Internal so consumer code can't catch the raw marker and swallow the
 * interrupt by accident.
 */
internal class PendingInterruptSignal(val payload: Any?) : RuntimeException()
