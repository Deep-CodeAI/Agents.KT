[← Back to README](../README.md)

# Human-in-the-loop (HITL)

A tool inside an agentic loop can pause the run and wait for typed external input — most often human input. The runtime captures a `SessionSnapshot`, the caller asks the human (or any external typed reply source), then resumes the loop. The model's view of the conversation stays continuous across the round-trip.

Two surfaces ship today, layered:

- **`interrupt(payload)`** — the raw primitive (#2488). Pause with an arbitrary payload, resume with an arbitrary `resumeWith` value.
- **`humanApproval { ... }`** — the typed approval gate (#2489). Sugar over `interrupt(ApprovalRequest(...))` with a sealed `HumanDecision` resume type and audited events.

Both build on the existing snapshot machinery: `FileSnapshotStore` for filename-safe persistence (#2753), the manifest-hash restore guard for fail-closed resume across an agent rebuild (#2754), per-agent memory restore (#2755).

---

## Raw `interrupt(payload)`

The minimal contract. Any tool executor can call `interrupt(...)`; the runtime throws `AgentInterruptException` and the caller resumes with a typed reply.

```kotlin
import agents_engine.core.AgentInterruptException
import agents_engine.core.FileSnapshotStore
import agents_engine.core.interrupt

val agent = agent<String, String>("Asker") {
    model { ollama("gpt-oss:120b-cloud") }
    tools {
        tool("ask_human", "Ask the user for clarification") { args ->
            interrupt(payload = args["question"] ?: "?")
        }
    }
    skills { skill<String, String>("ask", "ask") { tools("ask_human") } }
}

val store = FileSnapshotStore(Path.of("snapshots"))

try {
    agent("Where should I deploy?")
} catch (e: AgentInterruptException) {
    val question = e.payload as String
    store.save("session-42", e.snapshot)

    // Later — possibly after a process restart:
    val loaded = store.load("session-42")!!
    val reply = askHumanFromQueue(question)   // your code

    val finalOutput = agent.invokeSuspendResuming(
        input = "Where should I deploy?",
        resumeFrom = loaded,
        resumeWith = reply,
    )
    println(finalOutput)
}
```

**On resume**, the runtime synthesises a tool result message from `resumeWith` (rendered via `toLlmInput`, so `@Generable` types become JSON) and continues the loop. The model sees its previous assistant turn followed by the synthesised tool result — no replay, no re-prompting.

**Process-restart safety.** `FileSnapshotStore` keys the on-disk filename by SHA-256 hex of the session id (#2753), so external session ids — including hostile values like `"../../../etc/poisoned"` — stay inside the configured directory. The original id round-trips through the snapshot body for traceability.

**Manifest mismatch.** If you rebuild the agent with a different tool / permission set, resume fails closed with `SnapshotManifestMismatchException` (#2754). Pass `allowManifestMismatch = true` if you own the migration semantics.

---

## Typed `humanApproval { }`

A sharper API for the most common HITL pattern — "ask a human, then proceed / refuse / edit / respond." The reply is typed via a sealed `HumanDecision`.

```kotlin
import agents_engine.core.HumanDecision
import agents_engine.core.humanApproval
import kotlin.time.Duration.Companion.minutes

@Generable("A staged deploy plan")
data class DeployPlan(val service: String, val canaryPct: Int, val rollbackPlan: String)

val agent = agent<String, String>("Deployer") {
    model { ollama("gpt-oss:120b-cloud") }
    tools {
        tool("approve_deploy", "Approve the prepared deployment plan") { args ->
            humanApproval {
                title = "Deploy ${args["service"]} to production?"
                body = DeployPlan(
                    service = args["service"] as String,
                    canaryPct = 5,
                    rollbackPlan = "automatic on error rate > 1%",
                )
                timeout = 30.minutes
                defaultOnTimeout = HumanDecision.Rejected
            }
        }
    }
    skills { skill<String, String>("deploy", "deploy") { tools("approve_deploy") } }
}
```

### Sealed `HumanDecision`

```kotlin
sealed interface HumanDecision {
    object Approved : HumanDecision
    object Rejected : HumanDecision
    data class Edited(val payload: Any?) : HumanDecision      // human modified the plan
    data class Responded(val payload: Any?) : HumanDecision   // human gave free-form context
}
```

Each variant's behaviour after resume:

| Variant | What the model sees in the tool result |
|---|---|
| `Approved` | `Approved` |
| `Rejected` | `Rejected` |
| `Edited(payload)` | the rendered payload (JSON for `@Generable`, raw for primitives) |
| `Responded(payload)` | the rendered payload (typically a free-form string) |

The model continues from the synthesised tool result and proceeds accordingly.

### Timeout policy

`timeout` and `defaultOnTimeout` are **advisory**. The runtime can't enforce them inside a suspension — the human reply happens between `catch (AgentInterruptException)` and `invokeSuspendResuming(...)`. Your HITL layer honours the timeout and, on expiry, resumes with `resumeWith = request.defaultOnTimeout`.

`defaultOnTimeout = HumanDecision.Rejected` is the documented default. Fail-closed by design — sensitive actions should not auto-approve on operator absence.

---

## Audit events

`humanApproval { }` emits two `PipelineEvent` variants automatically. Pick them up via `agent.observe { }`:

```kotlin
agent.observe { event ->
    when (event) {
        is PipelineEvent.ApprovalRequested ->
            auditLog.row("approval/requested", event.title, event.hasBody, event.timeoutMs)
        is PipelineEvent.ApprovalDecided ->
            auditLog.row("approval/decided", event.decision, event.hasPayload)
        else -> { }
    }
}
```

**Field-only by design.** Bodies + payloads stay off the audit row — they can be high-volume (deploy plans, file contents) or PII-sensitive. The row carries: title, `hasBody: Boolean`, `timeoutMs: Long?`, `decision: String` (variant name), `hasPayload: Boolean`. The bodies are still in your application code; the runtime context (`requestId` / `sessionId` / `manifestHash`) correlates rows back to your own storage if needed.

Bridges pick the events up automatically:

| Bridge | Event name |
|---|---|
| `:agents-kt-otel` (OpenTelemetry) | `agent.approval.requested` / `agent.approval.decided` span events |
| `:agents-kt-langsmith` | `agent.approval.requested` / `agent.approval.decided` run events |
| `:agents-kt-langfuse` | `agent.approval.requested` / `agent.approval.decided` observations |
| `:agents-kt-observability` JSONL exporter | event class name in the `eventType` column |

Direct listener slots are also available:

```kotlin
agent.onApprovalRequested { title, hasBody, timeoutMs -> ... }
agent.onApprovalDecided { decision, hasPayload -> ... }
```

For raw `interrupt(...)` (not `humanApproval`), no approval-shaped events fire — the type-driven gating recognises only `ApprovalRequest` payloads and `HumanDecision` resume values.

---

## v1 constraints (worth knowing)

- **One outstanding interrupted call per resume.** If the model emits multiple tool calls in the same assistant turn and one of them interrupts, the remaining calls in that turn do not execute (the throw bubbles before them). Only the interrupted call's result is synthesised on resume. For predictable behaviour, design HITL tools so the model only calls one per turn (e.g. dedicated `approve_*` or `ask_human` tools).
- **Timeout is caller-honoured**, not runtime-enforced — see above.
- **`humanApproval` always throws** (Kotlin return type `Nothing`). The "value" the human chose arrives at the next model turn via the synthesised tool result, not as a function return. This is intentional — the alternative would require coroutine-suspension semantics that don't play well with provider HTTP calls.

---

## Where to read more

- [`docs/permission-manifest.md`](permission-manifest.md) — the manifest-hash restore guard that protects against resume across an agent rebuild.
- [`docs/regulated-deployment.md`](regulated-deployment.md) — where HITL fits in the audit story.
- Sources: `agents_engine/core/Interrupt.kt`, `agents_engine/core/HumanApproval.kt`, `agents_engine/core/Snapshot.kt`.
- Tests: `InterruptResumeTest.kt`, `HumanApprovalTest.kt`.
