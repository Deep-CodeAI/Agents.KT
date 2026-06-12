---
description: Source-file knowledge for agents_engine/composition/pipeline/PipelineSessionExtension.kt — pipeline.session(input) (#1745). Runs effectiveSessionExec — explicit sessionExec streams inner agents, null fallback runs execution surfacing only terminal events. Terminal Completed uses last agent's name. Channel.BUFFERED + SupervisorJob + Dispatchers.Default. Known gap: un-converted then overloads don't stream inner events. Call when the IDE LLM needs to reason about streaming a pipeline.
---

# `agents_engine/composition/pipeline/PipelineSessionExtension.kt` — streaming pipelines

Adds `Pipeline<IN, OUT>.session(input): AgentSession<OUT>` (#1745).

## Sequence

Runs `pipeline.effectiveSessionExec(input, emitter)`:
- When `sessionExec` is explicit, inner agents' events stream into the channel with their own `agentId`s.
- When `sessionExec` is null (the fallback), `execution(input)` runs and only the terminal event fires — inner events do NOT stream. Since #3866 every `then` overload populates `sessionExec` (operators chained mid-pipeline run through their internal emitter-aware `sessionInvoke` cores), so the null fallback only applies to a `Pipeline` constructed directly outside the `then` factories.

Terminal `Completed(agentId = lastAgent.name, output = result)` fires once `execution` (or `sessionExec`) returns.

## Failure handling

Exceptions from any agent in the chain propagate up through `execution`. Terminal event is `Failed(agentId, cause)`. `session.await()` rethrows.

## Channel + scope

`Channel.BUFFERED` consumed via `consumeAsFlow()`. The producer coroutine runs on `SupervisorJob() + Dispatchers.Default` — survives a single agent's failure without killing the channel before terminal event delivery.

## Consumer pattern

```kotlin
pipeline.session(input).events.collect { event ->
    when {
        event is AgentEvent.Token         -> ui.show(event.text)
        event is AgentEvent.SkillStarted  -> ui.markSkill(event.skillName)
        event is AgentEvent.Completed     -> ui.done(event.output)
        event is AgentEvent.Failed        -> ui.error(event.cause)
    }
}
```

## Related files

- `Pipeline.kt` — the type extended.
- `runtime/events/AgentSession.kt` — the returned session shape.
- `runtime/events/runAgentInSession.kt` — the per-stage streaming helper.
