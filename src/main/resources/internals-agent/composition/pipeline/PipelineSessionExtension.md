# `agents_engine/composition/pipeline/PipelineSessionExtension.kt` — streaming pipelines

Adds `Pipeline<IN, OUT>.session(input): AgentSession<OUT>` (#1745).

## Sequence

Runs `pipeline.effectiveSessionExec(input, emitter)`:
- When `sessionExec` is explicit, inner agents' events stream into the channel with their own `agentId`s.
- When `sessionExec` is null (the fallback), `execution(input)` runs and only the terminal event fires — inner events do NOT stream. This is a known gap for un-converted `then` overloads; see #1745 follow-ups for the per-operator session wiring.

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
