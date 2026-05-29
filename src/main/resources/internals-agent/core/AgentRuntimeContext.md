---
description: Source-file knowledge for agents_engine/core/AgentRuntimeContext.kt — runtime audit correlation context carried by PipelineEvent and AgentEvent. Defines AgentRuntimeContext(requestId, sessionId, manifestHash, attribution), the AttributionKeys canonical constants, the scoped ThreadLocal withAgentRuntimeContext helper, and how attribution propagates outward-in from a wrapping scope to per-invocation contexts. Call when reasoning about request/session IDs, manifest hash propagation, user/project/dialog attribution, or audit-event correlation.
---

# `agents_engine/core/AgentRuntimeContext.kt` — runtime audit context

Small context object attached to every `PipelineEvent` and `AgentEvent`.

```kotlin
data class AgentRuntimeContext(
    val requestId: String = UUID.randomUUID().toString(),
    val sessionId: String? = null,
    val manifestHash: String? = null,
    val attribution: Map<String, String> = emptyMap(),  // #2720
)
```

## Semantics

**Technical correlation:**
- `requestId` is a UUIDv4 generated per top-level invoke/session run.
- `sessionId` is set for `agent.session(input)` and composition session calls; also doubles as the resume key under `Agent.resumeOrStart(sessionId, input)` (#2418).
- `manifestHash` is the sha256 of the deterministic permission manifest once manifest generation exists; until then it is `null`.

**Business correlation (#2720):**
- `attribution` is a free-form `Map<String, String>` for deployer-defined identifiers. Empty by default — no behavior change unless a caller sets it.
- Three canonical keys have typed accessors on the context:

  ```kotlin
  val userId: String? get() = attribution[AttributionKeys.USER_ID]
  val projectId: String? get() = attribution[AttributionKeys.PROJECT_ID]
  val dialogId: String? get() = attribution[AttributionKeys.DIALOG_ID]
  ```

- `AttributionKeys` (sibling `object`) holds the three constants: `USER_ID = "userId"`, `PROJECT_ID = "projectId"`, `DIALOG_ID = "dialogId"`. Bridges and deployer code use these constants rather than literal strings so a future rename surfaces as a compile error.
- Arbitrary keys (`tenantId`, `keyOwner`, `customerId`, …) round-trip through `attribution[...]` — deployers extend the map freely without touching the upstream surface.
- `sessionId` vs `dialogId` is deliberate: `sessionId` is the framework's resume-keyed session boundary, `dialogId` is the deployer's notion (a chat thread, a workflow instance). Some deployments use both.

## Propagation

`withAgentRuntimeContext(context) { ... }` installs the context for the current producer scope with try/finally restoration. Session emitters also stamp the context onto forwarded `AgentEvent`s, so child coroutines and tool-emitted events keep the same audit IDs without wrapping or cloning exceptions.

Event classes use `AgentRuntimeContext.currentOrNew()` as their default context. Normal entry points install a context explicitly; the default keeps manually constructed events valid.

**Attribution flows outward-in.** `Agent.newRuntimeContext` (the per-invocation factory called by `invokeSuspend` / `session` / `invokeSuspendForSession`) reads the outer ThreadLocal scope and inherits its `attribution` map when constructing a fresh per-invocation context. `requestId` stays fresh per invocation; `sessionId` / `manifestHash` come from this invocation's parameters; attribution propagates from any wrapping scope. The bridge pattern (set attribution once at the session boundary, every nested event sees it) works without per-event plumbing.

```kotlin
withAgentRuntimeContext(
    AgentRuntimeContext.currentOrNew().copy(
        attribution = mapOf(
            AttributionKeys.USER_ID to userId,
            AttributionKeys.PROJECT_ID to projectId,
            AttributionKeys.DIALOG_ID to dialogId,
        ),
    ),
) {
    agent.session(input).events.collect { event ->
        // event.userId / event.projectId / event.dialogId all populated
        // event.attribution["customKey"] also available
    }
}
```

## Related files

- `PipelineEvent.kt` — post-hoc `Agent.observe { }` events expose `requestId`, `sessionId`, `manifestHash`, `attribution`, plus the three canonical convenience getters (`userId` / `projectId` / `dialogId`).
- `runtime/events/AgentEvent.kt` — streaming session events expose the same fields with the same convenience getters.
- `runtime/events/AgentSessionExtension.kt` — creates per-session context; inherits attribution from the wrapping scope.
- `Agent.kt` — `newRuntimeContext(sessionId)` builds the per-invocation context inheriting attribution from `AgentRuntimeContext.current()`; also holds the agent-level `manifestHash`.
