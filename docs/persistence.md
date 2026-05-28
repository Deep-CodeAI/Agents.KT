[← Back to README](../README.md)

## Snapshot / Resume

> Phase 2 of [#2386](../../issues/2386). Builds on the v1 spike ([#2416](../../issues/2416)) — see the design rationale there.

An agent's resumable state is its **message history + loop counters**. LLM turns are stateless (each call re-sends the whole message list), so resume re-enters the agentic loop seeded with a saved `SessionSnapshot` — no coroutine suspension involved. The snapshot is written at every turn boundary (after a tool round completes, before the next model call), so resume never re-runs a non-idempotent tool.

### Wire it in one block

```kotlin
import agents_engine.core.*
import java.nio.file.Path

val researcher = agent<String, String>("Researcher") {
    model { ollama("llama3") }
    persistence {
        store = FileSnapshotStore(Path.of("/var/lib/agents/snapshots"))
        autoSnapshot = AutoSnapshotPolicy.OnTurnComplete    // default
    }
    skills {
        skill<String, String>("research", "Researches a topic") {
            tools(searchTool, fetchTool)
        }
    }
}

// Load-or-fresh: returns a fresh run if no prior snapshot, resumes otherwise.
val answer = researcher.resumeOrStart(sessionId = "user-42", input = "tax-loss harvesting basics")
```

That's the whole user-facing surface for v1. The DSL hides the `executeAgentic(onTurnCheckpoint = …, resumeFrom = …)` plumbing that the spike exposed.

### Stores

| Store | Use when |
|---|---|
| `InMemorySnapshotStore` | tests, single-process resume across requests in the same JVM |
| `FileSnapshotStore(dir)` | on-disk durability; one JSON file per key, atomic temp-write + rename, so a crash mid-write never corrupts the live snapshot |

Both implement `SnapshotStore`, so deployers can wrap them with their own encryption, locking, or remote-backend layers (S3, Redis, Postgres). The framework does **no** distributed locking or encryption in the core — single-writer per `sessionId`, last-write-wins otherwise.

### Auto-snapshot policy

| Policy | Behavior |
|---|---|
| `AutoSnapshotPolicy.OnTurnComplete` *(default)* | snapshot after every completed tool round, before the next model call |
| `AutoSnapshotPolicy.Disabled` | DSL still binds the store but no automatic checkpoints fire — the seam for future explicit `snapshot(...)` calls |

### What's in a snapshot

```kotlin
data class SessionSnapshot(
    val messages: List<LlmMessage>,    // full conversation, including the saved system + user
    val turns: Int,
    val toolCalls: Int,
    val toolCallLimit: Int,            // honors #2412 onBudgetExceeded extensions
    val tokensUsed: TokenUsage?,
    val memory: Map<String, String>,   // MemoryBank state at the boundary
    val requestId: String,
    val sessionId: String?,
    val manifestHash: String?,         // safety anchor — see "Restore guard" below
)
```

### Restore semantics

`resumeOrStart(sessionId, input)`:

1. Calls `store.load(sessionId)`.
2. No snapshot ⇒ fresh run keyed by `sessionId` (so the first turn boundary writes back to the same key).
3. Snapshot present ⇒ the [restore guard](#manifest-hash-restore-guard) is consulted first; if it allows, the agentic loop is seeded with the saved messages, counters, and memory. The original `input` you pass is **ignored** for the loop's user message (the saved one is restored), but is still required by the signature so the type system stays clean.

When persistence is **not** configured, plain `Agent.invokeSuspend(input)` is byte-for-byte unchanged — no checkpointing, no behavior change, no cost.

### Manifest-hash restore guard

Every snapshot carries the `manifestHash` of the agent that wrote it. On resume, the guard compares it to the current agent's hash — refusing by default to silently restore state into a re-shaped agent. Without this check, a snapshot written by agent v1 could continue into agent v2 with different tools, policies, or providers, defeating the audit story carried by the [permission manifest](permission-manifest.md).

```kotlin
persistence {
    store = FileSnapshotStore(Path.of("/var/lib/agents/snapshots"))
    restoreGuard = RestoreGuardPolicy.Strict     // default
}
```

| Policy | Behavior on mismatch |
|---|---|
| `RestoreGuardPolicy.Strict` *(default)* | throws `SnapshotManifestMismatchException(sessionId, expected, actual)` |
| `RestoreGuardPolicy.WarnAndProceed` | logs at `WARNING` with both hashes, continues |
| `RestoreGuardPolicy.Allow` | continues silently — opt-in escape hatch for known-safe migrations |

The guard **only fires when both hashes are present**. A null on either side (snapshot pre-dates manifest attachment, or the current agent has no manifest computed) is treated as "no enforcement signal" rather than a mismatch — the resume continues silently.

A mismatch is the framework's way of asking you to make a deliberate decision: either bump the snapshot store generation, write a migration that opens the old conversation in a new agent, or — if you're sure the change is benign (a tool description tweak, a comment) — flip the guard to `WarnAndProceed` or `Allow` for that deployment.

### What's *not* snapshotted

- In-flight HTTP. Worst case on a crash mid-tool-call = lose the last turn (the next checkpoint is one model round away).
- The model client and the agent graph itself — those are re-created from your code on the next process start. The `manifestHash` captured in the snapshot is the safety anchor consulted by the [restore guard](#manifest-hash-restore-guard).
- Coroutine continuations. Mid-tool suspension is a separate, later concern that depends on the suspend-loop refactor ([#638](../../issues/638)).

### What's next

- **Composition snapshots** — extend `Snapshotable` to Pipeline / Forum / Loop / Branch so multi-agent topologies snapshot the whole tree, not just a leaf agent.
- **Mid-tool suspension** — true coroutine-continuation persistence (Phase 3 on #2386).

Track the umbrella at [#2386](../../issues/2386).
