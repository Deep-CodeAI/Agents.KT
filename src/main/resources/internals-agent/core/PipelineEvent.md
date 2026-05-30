---
description: Source-file knowledge for agents_engine/core/PipelineEvent.kt — the sealed PipelineEvent (SkillChosen, ToolCalled, ToolDenied, KnowledgeLoaded, ErrorOccurred, BudgetThreshold, ToolHallucinated, ApprovalRequested, ApprovalDecided) and the Agent.observe { } extension that chains it over the per-event listeners additively (#965, #2395, #2757, #2489). Every event carries AgentRuntimeContext fields requestId, sessionId, manifestHash (#1913). Call when the IDE LLM needs to reason about post-hoc observability vs the in-loop AgentEvent stream.
---

# `agents_engine/core/PipelineEvent.kt` — unified observability event

A typed sealed-interface union over the four per-event listener hooks an `Agent` exposes. Lets integrators wire telemetry with a single `when` block instead of four separate registrations (#965).

## Event variants

```kotlin
sealed interface PipelineEvent {
    val agentName: String
    val timestamp: Instant
    val requestId: String
    val sessionId: String?
    val manifestHash: String?

    data class SkillChosen(...,        skillName: String)
    data class ToolCalled(...,         toolName: String, arguments: Map<String, Any?>, result: Any?, toolPolicyRisk, usedDeclaredCapability)
    data class ToolDenied(...,         toolName: String, arguments: Map<String, Any?>, reason: String, toolPolicyRisk, usedDeclaredCapability)
    data class KnowledgeLoaded(...,    entryName: String, contentLength: Int)
    data class ErrorOccurred(...,      error: Throwable)
    data class BudgetThreshold(...,    reason: BudgetReason, usedPercent: Double)
    data class ToolHallucinated(...,   requestedName: String, arguments: Map<String, Any?>, allowedTools: List<String>)
    data class ApprovalRequested(...,  title: String, hasBody: Boolean, timeoutMs: Long?)
    data class ApprovalDecided(...,    decision: String, hasPayload: Boolean)
}
```

`agentName`, `timestamp`, `requestId`, `sessionId`, and `manifestHash` are present on every variant — sort, filter, attribute, and audit-correlate without inspecting the variant.

`ToolCalled` also carries `toolPolicyRisk` and `usedDeclaredCapability` from the executed `ToolDef` (#1915). The flag means "the tool declared at least one filesystem/network/environment capability"; it is audit metadata, not sandbox proof.

`ToolDenied` (#2395) is emitted when an `onBeforeToolCall` `Decision.Deny` blocks a call — the executor never runs, so it rides `onToolDenied` (not `onToolUse`/`ToolCalled`). It carries the denial `reason` plus the same `toolPolicyRisk`/`usedDeclaredCapability` audit fields, so a security log built on `observe { }` captures blocked attempts that would otherwise be invisible.

`ToolHallucinated` (#2757) fires when the model emits a tool name that is NOT in the active skill's allowlist (per #2476 the runtime recovers; this is the audit evidence). `allowedTools` is skill-scoped — no leak of the wider `agent.toolMap`. Distinct from `ToolDenied`: policy denial vs unknown name.

`ApprovalRequested` (#2489) fires before `AgentInterruptException` is thrown when a tool calls `humanApproval { }`. Field-only — `title` + `hasBody` + `timeoutMs`; the body itself stays off the audit row (PII discipline).

`ApprovalDecided` (#2489) fires on resume when `resumeWith` is a `HumanDecision`. `decision` is the variant name (`Approved` / `Rejected` / `Edited` / `Responded`); `hasPayload` flags whether the variant carried a payload.

## Wiring

```kotlin
val tracer = agent<String, String>("tracer") { /* ... */ }

tracer.observe { event ->
    when (event) {
        is PipelineEvent.SkillChosen       -> emit("skill",   event.skillName)
        is PipelineEvent.ToolCalled        -> emit("tool",    "${event.toolName}:${event.toolPolicyRisk}")
        is PipelineEvent.ToolDenied        -> emit("deny",    "${event.toolName}: ${event.reason}")
        is PipelineEvent.KnowledgeLoaded   -> emit("know",    event.entryName)
        is PipelineEvent.ErrorOccurred     -> emit("error",   event.error.message ?: "<no msg>")
        is PipelineEvent.BudgetThreshold   -> emit("budget",  "${event.reason}@${event.usedPercent}")
        is PipelineEvent.ToolHallucinated  -> emit("halluc",  "${event.requestedName} not in ${event.allowedTools}")
        is PipelineEvent.ApprovalRequested -> emit("approve", "request: ${event.title}")
        is PipelineEvent.ApprovalDecided   -> emit("approve", "decided: ${event.decision}")
    }
}
```

## Composability

`observe { }` is **additive** — it does NOT replace prior listeners. Each variant fetches the prior listener, sets a new one that invokes BOTH the prior listener AND the unified handler. Multiple `observe { }` calls stack additively.

This means a single agent can be observed by both per-listener handlers (set via `onSkillChosen`, `onToolUse`, etc.) AND one or more `observe { }` consumers without interference.

## Post-freeze safety

Listener slots are **exempt from the freeze**. You can wire observability after `agent { }` returns. This is intentional — instrumentation often happens at composition time after the agent has been constructed by some other module.

## Scope: subset of the PRD §10.2 hierarchy

The four variants here are the SHIPPED subset. `TextDelta`, `BudgetWarning`, `SubAgentSpawned`, `ContextCompacted`, `Pipeline*`, `Inference*` are part of the PRD's full event model but depend on infrastructure that is not part of this file:

- Streaming token deltas → `AgentEvent.Token` (`AgentSession.kt`).
- Budget threshold warnings → `onBudgetThreshold` listener on `Agent`.
- Sub-agent spawn → forum / branch / swarm composition events.
- Context compaction → not shipped.

The split is "post-hoc per-skill events" (here) vs "in-loop streaming events" (`AgentEvent`).

## Related files

- `Agent.kt` — the per-event listener slots (`skillChosenListener`, `toolUseListener`, `toolDeniedListener`, `knowledgeUsedListener`, `errorListener`, `budgetThresholdListener`) that `observe { }` chains over.
- `runtime/events/AgentEvent.kt` — the in-loop streaming event surface (different concern).
- `runtime/events/AgentSession.kt` — `Flow<AgentEvent<OUT>>` consumer surface.
