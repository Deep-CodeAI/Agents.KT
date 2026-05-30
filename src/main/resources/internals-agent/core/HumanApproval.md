---
description: Source-file knowledge for agents_engine/core/HumanApproval.kt — typed human approval gate on top of the #2488 interrupt primitive (#2489). ApprovalRequest(title, body, timeout, defaultOnTimeout) + sealed HumanDecision { Approved, Rejected, Edited(payload), Responded(payload) } + ApprovalBuilder DSL + free function humanApproval { } : Nothing. The runtime emits field-only PipelineEvent.ApprovalRequested (before throw) and ApprovalDecided (on resume when resumeWith is a HumanDecision); bodies stay off the audit row for PII discipline. Timeout is advisory — caller honours it via defaultOnTimeout because the human reply happens between catch (AgentInterruptException) and the next invokeSuspendResuming call. Call when reasoning about typed approvals, sealed decision routing, or human-gated agent loops.
---

# `agents_engine/core/HumanApproval.kt` — human approval gate

`humanApproval { }` pauses an agentic loop for typed human input. Sugar on top of `interrupt(ApprovalRequest(...))` — no new exception type or resume path.

## The shape

```kotlin
tool("approve_deploy") { args ->
    humanApproval {
        title = "Deploy to production?"
        body = deploymentPlan         // typed @Generable or anything toLlmInput-renderable
        timeout = 30.minutes
        defaultOnTimeout = HumanDecision.Rejected
    }
    // throws AgentInterruptException carrying the ApprovalRequest
}

// Caller side:
val decision: HumanDecision = askHuman(exception.payload as ApprovalRequest)
agent.invokeSuspendResuming(
    input = original,
    resumeFrom = exception.snapshot,
    resumeWith = decision,
)
```

## Sealed `HumanDecision`

Four variants, not a boolean:

| Variant | Carries | When to use |
|---|---|---|
| `Approved` | — | Proceed with the plan as-is. |
| `Rejected` | — | Refuse. Sensitive actions should fail-closed here. |
| `Edited(payload)` | the modified plan | The human changed the plan; runtime forwards the new value. |
| `Responded(payload)` | a free-form reply | Human gave context (e.g. "ask the user about X first") instead of a strict approve/reject. |

`Edited.payload` is typically the same shape as `ApprovalRequest.body` but isn't constrained to it. Both `Edited` and `Responded` payloads render through `toLlmInput`, so `@Generable` types become JSON, primitives stay raw, strings stay strings.

## Audit events

Two `PipelineEvent` variants fire automatically when the runtime detects the typed payload / resumeWith:

- `PipelineEvent.ApprovalRequested(title, hasBody, timeoutMs, ...)` — fires BEFORE the throw. Field-only audit row; `body` is not copied in.
- `PipelineEvent.ApprovalDecided(decision, hasPayload, ...)` — fires on resume. `decision` is the variant name; `hasPayload` flags whether Edited/Responded had a payload.

PII discipline: bodies + payloads stay off the audit row by design. Bridges (OTel / LangSmith / Langfuse) and the JSONL audit exporter pick them up via the usual `Agent.observe { }` seam.

## Listener slots

Direct listeners (mirroring the existing per-listener pattern):

```kotlin
agent.onApprovalRequested { title, hasBody, timeoutMs -> log(title) }
agent.onApprovalDecided { decision, hasPayload -> log(decision) }
```

Or via `observe { }`:

```kotlin
agent.observe { event ->
    when (event) {
        is PipelineEvent.ApprovalRequested -> auditLog.row("approval/requested", event)
        is PipelineEvent.ApprovalDecided -> auditLog.row("approval/decided", event)
        else -> { }
    }
}
```

## Timeout

`ApprovalRequest.timeout` and `defaultOnTimeout` are **advisory** — the runtime can't enforce them inside a suspension because the human reply happens BETWEEN `catch (AgentInterruptException)` and the next `invokeSuspendResuming(...)` call. The contract: the caller's HITL layer honours the timeout and, on expiry, resumes with `resumeWith = request.defaultOnTimeout`.

`defaultOnTimeout = HumanDecision.Rejected` is the documented default — fail-closed for a regulated runtime.

## Composition

- Builds entirely on #2488 interrupt — no new exception type.
- Same persistence story: `FileSnapshotStore` (#2753), manifest-hash restore guard (#2754), per-agent memory restore (#2755).
- The synthesised tool message on resume uses the same `toLlmInput`-based rendering as the raw `interrupt()` path.

## Related files

- `core/Interrupt.kt` — the underlying primitive. `humanApproval` calls `interrupt(payload = ApprovalRequest(...))`.
- `core/PipelineEvent.kt` — `ApprovalRequested` + `ApprovalDecided` variants.
- `core/Agent.kt` — `approvalRequestedListener` + `approvalDecidedListener` slots, `onApprovalRequested` + `onApprovalDecided` setters.
- `model/AgenticLoop.kt` — type-driven event emission at the interrupt-catch site (ApprovalRequest payload) and resume entry (HumanDecision resumeWith).
