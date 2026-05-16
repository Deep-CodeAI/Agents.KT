# `agents_engine/runtime/events/AgentEvent.kt` — typed session event union

The events flowing through `Agent.session(input).events`. Sealed so consumers can write exhaustive `when` matches today.

## Variants

```kotlin
sealed interface AgentEvent<out OUT> {
    val agentId: String     // every event carries provenance

    data class Token(agentId, skillName, text)                          : AgentEvent<Nothing>   // step 3
    data class ToolCallStarted(agentId, skillName, callId, toolName)    : AgentEvent<Nothing>   // step 3
    data class ToolCallArgumentsDelta(agentId, callId, deltaJson)       : AgentEvent<Nothing>   // step 3
    data class ToolCallFinished(agentId, callId, result)                : AgentEvent<Nothing>   // step 3

    data class SkillStarted(agentId, skillName)                         : AgentEvent<Nothing>
    data class SkillCompleted(agentId, skillName, tokenUsage)           : AgentEvent<Nothing>

    data class Completed(agentId, output: OUT)                          : AgentEvent<OUT>
    data class Failed(agentId, cause: Throwable)                        : AgentEvent<Nothing>
}
```

## Step 2 vs step 3 emission

Today (step 2 of #1736), the framework emits:
- `SkillStarted` → at the start of every skill dispatch.
- `SkillCompleted(tokenUsage)` → when the skill returns, with cumulative usage.
- `Completed(output)` → terminal, carries the typed `OUT`.
- `Failed(cause)` → terminal on any throw.

Step 3 will rewire the agentic loop onto an emitter and surface:
- `Token` per LLM text chunk.
- `ToolCallStarted` / `ArgumentsDelta` / `Finished` per tool call.

The sealed hierarchy is COMPLETE today so consumer code that handles all variants compiles and works against future framework versions without modification.

## `agentId` provenance

Every event carries `agentId` — the name of the agent that emitted it. Composition operators (`then`, `Pipeline`, `Branch`, `wrap`, `Swarm`) preserve provenance: events from an inner agent in a pipeline carry the inner agent's name, not the pipeline's. Consumers demultiplex by `agentId` to build per-agent timelines.

## Typing trick: `AgentEvent<Nothing>` for non-OUT variants

Only `Completed(output: OUT)` carries the typed `OUT` payload. Every other subtype is `AgentEvent<Nothing>` — so events flow through any `AgentSession<OUT>` regardless of `OUT`. This is Kotlin's variance system used to let one event hierarchy work across all session types.

## Related files

- `AgentSession.kt` — the handle that exposes a `Flow<AgentEvent<OUT>>`.
- `AgentSessionExtension.kt` — the `session(input)` entry point.
- `model/StreamingAggregator.kt` — internal producer of these events.
- `core/PipelineEvent.kt` — the post-hoc per-skill observability surface (different concern).
