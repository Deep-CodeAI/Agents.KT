---
description: Source-file knowledge for agents_engine/core/Interrupt.kt — HITL interrupt/resume primitive (#2488). Free function interrupt(payload) throws PendingInterruptSignal; the agentic loop catches it inside the tool-execution branch, builds a SessionSnapshot at the pre-tool-result boundary with pendingInterruptCallId set, fires onTurnCheckpoint, and throws AgentInterruptException(snapshot, payload, pendingToolCallId). On resume, Agent.invokeSuspendResuming(..., resumeWith = reply) synthesises the orphan tool's result message via toLlmInput. Composes with FileSnapshotStore (#2753), manifest-hash restore guard (#2754), and per-agent memory restore (#2755). v1 constraint: one outstanding interrupted call per resume. Call when reasoning about HITL pause/resume, typed external input mid-loop, or process-restart deterministic continuation.
---

# `agents_engine/core/Interrupt.kt` — HITL interrupt/resume primitive

`interrupt(payload)` pauses an agentic loop from inside a tool executor and surfaces a `SessionSnapshot` + `payload` to the caller for human-in-the-loop input.

## The shape

```kotlin
tool("ask_human") { args ->
    interrupt(payload = args["question"])
    // throws AgentInterruptException — never returns
}

// Caller side:
val output = try {
    agent("kick off")
} catch (e: AgentInterruptException) {
    store.save(sessionId, e.snapshot)
    val reply = askHuman(e.payload)
    agent.invokeSuspendResuming(
        input = "kick off",
        resumeFrom = e.snapshot,
        resumeWith = reply,
    )
}
```

## Pieces

- `interrupt(payload: Any?): Nothing` — public free function. Throws the internal `PendingInterruptSignal` marker.
- `AgentInterruptException(snapshot, payload, pendingToolCallId)` — surfaced to the caller. `snapshot.pendingInterruptCallId` names the tool call that's waiting.
- `internal class PendingInterruptSignal(payload)` — marker thrown by `interrupt()`. The agentic loop catches it; consumer code cannot (internal visibility).

## What the agentic loop does

1. The for-call loop in `executeAgentic` wraps each executor call in a try/catch.
2. On `PendingInterruptSignal` catch:
   - Builds the snapshot at the pre-tool-result boundary: messages contain the assistant tool-calls turn but NOT a tool result for the interrupted call.
   - Memory slice is per-agent (`MemoryBank.snapshotForAgent(agent.name)`) per #2755.
   - Sets `pendingInterruptCallId = effectiveCall.callId` (or a synthetic id for non-streaming callers).
   - Fires `onTurnCheckpoint(snapshot)` so the caller can persist.
   - Throws `AgentInterruptException(snapshot, payload, pendingToolCallId)`.
3. `executeToolWithBudgetHandlingEvents` special-cases the signal so it does NOT emit `ToolCallFinished(isError = true)` — an interrupt is not an error.

## What the resume path does

When `invokeSuspendResuming(..., resumeFrom = snapshot, resumeWith = reply)` is called:

1. The runtime checks `snapshot.pendingInterruptCallId`.
2. If non-null, `resumeWith` must be non-null (else `require` fails).
3. The runtime synthesises an `LlmMessage(role = "tool", content = toLlmInput(resumeWith))` and appends it to the restored message list.
4. The loop continues from the next model call. The model sees its prior assistant turn followed by the synthesised tool result; the round-trip is invisible.

The OpenAI adapter pairs assistant `tool_calls` to subsequent `tool` messages positionally, so the call_id only needs to live on the snapshot (for resume detection) — not on `LlmMessage`.

## Composition

- Snapshot persistence: any `SnapshotStore`. `FileSnapshotStore` (#2753) is filename-safe so external session ids can flow into the store.
- Manifest-hash restore guard (#2754) applies — refuses to resume across a manifest change unless `allowManifestMismatch = true`.
- Memory restore is per-agent (#2755) — shared-bank topologies safe.
- `SessionSnapshot.pendingInterruptCallId` round-trips through `SnapshotJson`, so a process-restart resume is deterministic.
- Composition operators (Pipeline / Branch / Loop / Parallel) propagate `AgentInterruptException` unchanged; only the leaf agent owns the snapshot.

## v1 constraint

One outstanding interrupted call per resume. If the model emits multiple tool calls in the same assistant turn and one of them interrupts, the remaining calls in that turn are NOT executed (the throw bubbles before them). Only the interrupted call's result is synthesised on resume. Wire-level outcome on resume: most providers will accept the partial tool-result set; some may reject. For predictable behaviour, use `humanApproval`-style tools that are the only call the model issues per turn.

## Related files

- `core/HumanApproval.kt` — sugar on top of `interrupt`. `humanApproval { title = ...; body = ...; timeout = ...; defaultOnTimeout = ... }` calls `interrupt(ApprovalRequest(...))`.
- `core/Snapshot.kt` — `SessionSnapshot.pendingInterruptCallId` field + JSON encode/decode.
- `model/AgenticLoop.kt` — catch + capture + throw at the executor try/catch; synthesis at the resume entry.
- `core/Agent.kt` — `invokeSuspendResuming(..., resumeWith)` public seam.
- `core/PipelineEvent.kt` — `ApprovalRequested` and `ApprovalDecided` variants fire when payload / resumeWith are typed approval values.
