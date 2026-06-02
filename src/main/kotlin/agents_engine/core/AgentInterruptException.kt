package agents_engine.core

/**
 * Surfaced to the caller of `agent(input)` / `agent.invokeSuspendResuming(...)`
 * when a tool executor called [interrupt]. Carries:
 *
 * - [snapshot] — the [SessionSnapshot] at the pre-tool-result boundary.
 *   The `messages` field contains every turn UP TO the assistant turn
 *   that emitted the interrupted call, but does NOT contain the tool
 *   result for that call (the runtime will synthesise it on resume).
 *   `snapshot.pendingInterruptCallId` names the call that's waiting.
 * - [payload] — whatever the tool passed to [interrupt]. Typically a
 *   question to render for the human.
 * - [pendingToolCallId] — the call_id of the tool whose result is pending.
 *   Also present on `snapshot.pendingInterruptCallId`; surfaced here for
 *   convenience when the caller doesn't want to inspect the snapshot.
 *
 * Resume flow:
 *
 * ```kotlin
 * val output = try {
 *     agent("kick off")
 * } catch (e: AgentInterruptException) {
 *     store.save(sessionId, e.snapshot)
 *     val reply = askHuman(e.payload)
 *     agent.invokeSuspendResuming(
 *         input = "kick off",
 *         resumeFrom = e.snapshot,
 *         resumeWith = reply,
 *     )
 * }
 * ```
 */
class AgentInterruptException(
    val snapshot: SessionSnapshot,
    val payload: Any?,
    val pendingToolCallId: String?,
) : RuntimeException(
    "Agent interrupted with payload=${payload?.let { it::class.simpleName + "(...)" } ?: "null"}. " +
        "Resume via invokeSuspendResuming(..., resumeFrom = exception.snapshot, resumeWith = reply).",
)
