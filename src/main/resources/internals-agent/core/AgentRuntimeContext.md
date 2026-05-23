---
description: Source-file knowledge for agents_engine/core/AgentRuntimeContext.kt — runtime audit correlation context carried by PipelineEvent and AgentEvent. Defines AgentRuntimeContext(requestId, sessionId, manifestHash), a scoped ThreadLocal withAgentRuntimeContext helper, and event defaults. Call when reasoning about request/session IDs, manifest hash propagation, or audit-event correlation.
---

# `agents_engine/core/AgentRuntimeContext.kt` — runtime audit context

Small context object attached to every `PipelineEvent` and `AgentEvent`.

```kotlin
data class AgentRuntimeContext(
    val requestId: String = UUID.randomUUID().toString(),
    val sessionId: String? = null,
    val manifestHash: String? = null,
)
```

## Semantics

- `requestId` is a UUIDv4 generated per top-level invoke/session run.
- `sessionId` is set for `agent.session(input)` and composition session calls.
- `manifestHash` is the sha256 of the deterministic permission manifest once manifest generation exists; until then it is `null`.

## Propagation

`withAgentRuntimeContext(context) { ... }` installs the context for the current producer scope with try/finally restoration. Session emitters also stamp the context onto forwarded `AgentEvent`s, so child coroutines and tool-emitted events keep the same audit IDs without wrapping or cloning exceptions.

Event classes use `AgentRuntimeContext.currentOrNew()` as their default context. Normal entry points install a context explicitly; the default keeps manually constructed events valid.

## Related files

- `PipelineEvent.kt` — post-hoc `Agent.observe { }` events expose `requestId`, `sessionId`, `manifestHash`.
- `runtime/events/AgentEvent.kt` — streaming session events expose the same fields.
- `runtime/events/AgentSessionExtension.kt` — creates per-session context.
- `Agent.kt` — creates per-invoke context and holds the agent-level `manifestHash`.
