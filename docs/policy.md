[← Back to README](../README.md)

# Declarative policy

`policy { }` is a thin declarative DSL on `Agent` that compiles into existing enforcement surfaces — the HITL interrupt gate (#2488 / #2489), audit-bridge redaction, and the permission manifest. No new runtime check is introduced; everything is sugar over machinery that already exists.

```kotlin
agent<String, String>("Deployer") {
    // ... model / tools / skills ...
    policy {
        requireHumanApprovalFor("send_email", "deploy", "refund")
        redact("apiKey", "password", "Authorization")
    }
}
```

Three pieces today:

- **`requireHumanApprovalFor(tool, ...)`** — the named tools pause for human approval before their executor runs. Composes with the [HITL](hitl.md) primitive: throws `AgentInterruptException` carrying an `ApprovalRequest`; the caller resumes with a `HumanDecision`.
- **`redact(field, ...)`** — field-name redaction in observability bridges. The LangSmith and Langfuse bridges read the field list at construction and scrub matching field values in tool argument writeouts to `"[REDACTED]"`.
- **Permission manifest entry** — the policy is recorded in the agent's section of the permission manifest, and the manifest SHA-256 hash covers it. An auditor signs off on the manifest; the same hash flows into every runtime audit event so signed-off policy is verifiable from logs.

Deferred to a follow-up ticket: **`denyToolsForRole(role, ...)`** — needs an `AgentRoleContext` propagation mechanism that the runtime doesn't have yet.

---

## `requireHumanApprovalFor(names)`

Gates the listed tools with `humanApproval { }`. The agentic loop, before invoking each gated tool's executor, fires:

```kotlin
humanApproval {
    title = "Approve tool call: $name"
    body = call.arguments
}
```

That throws `AgentInterruptException` with an `ApprovalRequest` payload. The caller asks the human and resumes:

```kotlin
val agent = agent<String, String>("Mailer") {
    model { ollama("gpt-oss:120b-cloud") }
    tools {
        tool("send_email", "Send an email") { args ->
            actuallySendEmail(args)
        }
    }
    skills { skill<String, String>("send", "") { tools("send_email") } }
    policy { requireHumanApprovalFor("send_email") }
}

try {
    agent("email user@example.com about the meeting")
} catch (e: AgentInterruptException) {
    val request = e.payload as ApprovalRequest
    val decision = askHuman(request)              // HumanDecision
    agent.invokeSuspendResuming(
        input = original,
        resumeFrom = e.snapshot,
        resumeWith = decision,
    )
}
```

### Decision dispatch

| `HumanDecision` | What the runtime does |
|---|---|
| `Approved` | Original tool runs with **original args**. |
| `Edited(payload)` | Original tool runs with **edited args** (`payload as Map<String, Any?>`). |
| `Rejected` | Tool does **NOT** run. Model sees `"Approval rejected by human; tool 'name' was not executed."` as the tool result. |
| `Responded(payload)` | Tool does **NOT** run. Model sees `toLlmInput(payload)` as the tool result. |

`Approved` / `Edited` are transparent — the model sees the actual executor output as if the call ran normally. `Rejected` / `Responded` short-circuit, and the model sees a structured response without the tool running. From the model's perspective the round-trip is invisible in all four cases.

### Validation

- Unknown tool name in `requireHumanApprovalFor(...)` fails fast at `agent { }` construction (same philosophy as #631 for skill tool names — typos surface at build time, not as silent runtime no-ops).
- `HumanDecision.Edited.payload` must be a `Map<String, Any?>`. Other types fail at resume with a clear error naming the offending tool.
- Resume from a policy-gate snapshot with a non-`HumanDecision` `resumeWith` fails fast.

### Audit events

Same as `humanApproval { }` directly: `PipelineEvent.ApprovalRequested` fires before the throw, `PipelineEvent.ApprovalDecided` fires on resume. Field-only audit rows (no body / payload — see [docs/hitl.md](hitl.md) for the PII discipline).

---

## `redact(fields)`

Field-name redaction for observability-bridge audit writeouts. Matching field values in tool arguments are replaced with `"[REDACTED]"` before being serialised into the bridge's audit trail.

```kotlin
agent {
    policy { redact("apiKey", "password", "Authorization") }
}

val bridge = LangSmithBridge(
    apiKey = ...,
    project = "audit",
    redactionFields = agent.policy.redactionFields,    // wire from policy
)
agent.observe(bridge)
```

**Where it applies:**

| Surface | Behaviour |
|---|---|
| `:agents-kt-langsmith` bridge | `AgentEvent.ToolCallFinished.arguments` map is routed through `redactArguments(args, redactionFields)` before the LangSmith run inputs are serialised. |
| `:agents-kt-langfuse` bridge | Same — `args` in the Langfuse tool span input is redacted. |
| `:agents-kt-otel` bridge | No change. OTel already records only `tool.arguments.type` + `tool.arguments.delta.length`, never raw args. |
| `:agents-kt-observability` JSONL exporter | No change. By design the JSONL audit row omits arguments entirely (see `JsonlAuditExporter.kt:30` comment). |

**Semantics:**

- Case-sensitive match on the top-level keys of the args map.
- Recurses into nested `Map<String, Any?>` values so `headers: { Authorization: ... }` is covered.
- Does NOT recurse into `List` entries — per-element redaction is out of scope for v1.
- Empty `redactionFields` returns the input map unchanged (no allocation on the default-policy path).

You can also call the helper directly:

```kotlin
import agents_engine.core.redactArguments

val cleaned = redactArguments(args, setOf("apiKey", "password"))
```

---

## Permission manifest

The policy block is recorded in each agent's section of the permission manifest (#1912), alongside `guardrails` and `humanOversight`:

```json
"policy": {
  "approvalRequiredTools": ["deploy", "refund", "send_email"],
  "redactionFields": ["apiKey", "password", "token"]
}
```

Both lists are sorted alphabetically so the manifest is byte-deterministic across runs and JVMs. Empty policy still produces the section (`"approvalRequiredTools": [], "redactionFields": []`) — explicit absence rather than missing field.

**Hash coverage:** the manifest SHA-256 is computed over the whole root map, so any change to the policy block changes the hash. Combined with the [manifest-hash restore guard (#2754)](permission-manifest.md), a resume attempt against an agent whose policy has drifted from the signed-off manifest fails closed with `SnapshotManifestMismatchException` (unless the caller explicitly opts in with `allowManifestMismatch = true`).

**Why this matters for the audit story:** an auditor signs off on a manifest hash. Every runtime audit event carries that hash. A reviewer can verify, post-hoc, that every audited action ran under the policy that was approved — drift between signed-off and running policy is detectable.

---

## What's still ahead

- **`denyToolsForRole(role, ...)`** — needs an `AgentRoleContext` propagation mechanism. Filed for a follow-up.
- **List-element redaction** — `redact(...)` doesn't recurse into List<Map<...>>. Add a v2 if a real use case surfaces.

---

## Related docs

- [`docs/hitl.md`](hitl.md) — the interrupt primitive and `humanApproval { }` gate that `requireHumanApprovalFor` is sugar over.
- [`docs/permission-manifest.md`](permission-manifest.md) — manifest semantics and the SHA-256 hash that covers the policy section.
- [`docs/observability.md`](observability.md) — the bridges that consume `redactionFields`.
- [`docs/threat-model.md`](threat-model.md) — what the runtime owns vs what the deployer owns.

Sources: `agents_engine/core/Policy.kt`, `agents_engine/model/AgenticLoop.kt` (gate-trigger + resume dispatch), `agents-kt-manifest/.../PermissionManifest.kt` (manifest emission), `agents-kt-{langsmith,langfuse}/.../*Bridge.kt` (redaction wiring).

Tests: `PolicyApprovalGateTest.kt`, `RedactArgumentsTest.kt`, `PermissionManifestTest.kt`.
