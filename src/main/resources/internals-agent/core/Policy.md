---
description: Source-file knowledge for agents_engine/core/Policy.kt — declarative policy DSL (#2490). Three sub-features: (a) requireHumanApprovalFor(names) gates listed tools with humanApproval{} — agentic loop fires interrupt BEFORE the executor, dispatches by HumanDecision on resume (Approved/Edited execute the tool, Rejected/Responded synth a refusal/payload result); (b) redact(fields) — observability bridges (LangSmith / Langfuse) wired to call redactArguments(args, fields) before tool-arg writeouts; (c) policy block emitted to permission manifest under "policy", sorted for byte-determinism, covered by the manifest SHA-256. No new runtime check — sugar over existing enforcement surfaces (#2488 interrupt, #2489 humanApproval, #1912 manifest, bridges). denyToolsForRole is deferred — no role-context infra yet. Call when reasoning about declarative policy authoring, approval gates, audit redaction, or manifest hash coverage of policy.
---

# `agents_engine/core/Policy.kt` — declarative policy DSL

`policy { }` is sugar over existing enforcement surfaces. Compiles into:

- **HITL approval gates** — `requireHumanApprovalFor` triggers `humanApproval { }` at the call site (#2488 / #2489).
- **Audit redaction** — `redact` is read by observability bridges and scrubs matching argument fields before writeout.
- **Permission manifest** — the policy is emitted into the agent's manifest section, sorted, hashed.

## Shape

```kotlin
data class Policy(
    val approvalRequiredTools: Set<String> = emptySet(),
    val redactionFields: Set<String> = emptySet(),
)

class PolicyBuilder {
    fun requireHumanApprovalFor(vararg toolNames: String)
    fun redact(vararg fieldNames: String)
}

// Agent.kt:
var policy: Policy = Policy.EMPTY        // private set
fun policy(block: PolicyBuilder.() -> Unit)
```

## `requireHumanApprovalFor` mechanics

At agent construction, `validate()` checks that every listed name is in `agent.toolMap` (same fail-fast philosophy as #631 for skill tool names).

At runtime, the agentic loop's tool-call branch checks `call.name in agent.policy.approvalRequiredTools`. If true AND we're not already past the gate for this exact callId:

1. Fire `humanApproval { title = "Approve tool call: $name"; body = call.arguments }` — throws `PendingInterruptSignal`.
2. The existing #2488 catch builds a `SessionSnapshot` with `pendingApprovalGate = true` (vs `false` for plain `interrupt()`).
3. `AgentInterruptException` propagates to the caller.

On resume:

- The runtime sees `snapshot.pendingApprovalGate == true` and refuses to use the regular #2488 synthesis path.
- Instead, it requires `resumeWith is HumanDecision`, finds the pending call in `messages.last { it.role == "assistant" && !toolCalls.isNullOrEmpty() }` by callId (with last-call fallback for synthetic ids), looks up the tool in `allowedToolMap`, and dispatches:

  | Decision | Action |
  |---|---|
  | `Approved` | Run executor with original args. Result becomes the tool message. |
  | `Edited(payload)` | Run executor with edited args (`payload as Map<String, Any?>` — else error). |
  | `Rejected` | Don't run. Synthesise `"Approval rejected by human; tool 'X' was not executed."`. |
  | `Responded(payload)` | Don't run. Synthesise `toLlmInput(payload)`. |

- `approvalDecidedListener` fires before the dispatch. Wired through `Agent.observe { }` to `PipelineEvent.ApprovalDecided`.

## `redact` mechanics

`redactArguments(args, fields)` is a public top-level function in this file. Replaces matching top-level keys with `"[REDACTED]"`. Recurses into nested `Map<String, Any?>`. Case-sensitive. Empty fields → returns input unchanged (no allocation).

Bridges read `redactionFields` at construction:

```kotlin
LangSmithBridge(apiKey = ..., project = ..., redactionFields = agent.policy.redactionFields)
LangfuseBridge(publicKey = ..., secretKey = ..., redactionFields = agent.policy.redactionFields)
```

The bridges call `redactArguments(event.arguments, redactionFields)` before serialising args into traces. Default empty set = no change in behaviour for callers who don't wire the policy.

OTel bridge: no change (already records only `tool.arguments.type` + delta length).
JSONL audit exporter: no change (already omits args entirely).

## Manifest emission

In `:agents-kt-manifest`, each agent's section gains:

```json
"policy": {
  "approvalRequiredTools": ["...", "..."],   // sorted
  "redactionFields": ["...", "..."]          // sorted
}
```

Sorted because `Set` iteration order is undefined; without sorting the same policy could produce different manifest hashes on different JVMs.

The manifest SHA-256 is computed over the whole root map → automatic hash coverage of the policy. Combined with #2754 (manifest-hash restore guard), a resume attempt across a policy change fails closed with `SnapshotManifestMismatchException`.

## State on snapshot

`SessionSnapshot.pendingApprovalGate: Boolean = false` — set true at the catch site when the throw came from a policy gate (not a user `interrupt()`). Serialised through `SnapshotJson` so process-restart resume still distinguishes gate snapshots.

## Out of scope (v1)

- `denyToolsForRole(role, ...)` — needs `AgentRoleContext` propagation. Filed for follow-up.
- List-element redaction (recursing into `List<Map<String, Any?>>`). Add when a use case surfaces.

## Related files

- `core/Interrupt.kt` — the throw mechanism `humanApproval { }` rides on.
- `core/HumanApproval.kt` — the typed wrapper + sealed `HumanDecision`.
- `core/Snapshot.kt` — `SessionSnapshot.pendingApprovalGate` field + JSON encode/decode.
- `model/AgenticLoop.kt` — gate-trigger inside the for-call try, gate-resume dispatch at the resume entry.
- `core/PipelineEvent.kt` — `ApprovalRequested` / `ApprovalDecided` variants (#2489); `redactionFields` is bridge-side, not on the event.
- `agents-kt-manifest/.../PermissionManifest.kt` — `policyManifest()` helper + emission.
- `agents-kt-langsmith/.../LangSmithBridge.kt`, `agents-kt-langfuse/.../LangfuseBridge.kt` — `redactionFields` ctor param + `redactArguments` call before `args` writeout.
