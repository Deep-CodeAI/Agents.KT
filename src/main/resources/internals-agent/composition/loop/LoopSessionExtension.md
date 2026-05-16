---
description: Source-file knowledge for agents_engine/composition/loop/LoopSessionExtension.kt — loop.session(input) (#1749). Iterations run serially (loops are sequential — events interleave one iteration at a time). Same termination rules as non-streaming. maxIterations breach → Failed. Constructed outside factory functions falls back to non-streaming execution. Call when the IDE LLM needs to reason about streaming a loop.
---

# `agents_engine/composition/loop/LoopSessionExtension.kt` — streaming loops

Adds `Loop<IN, OUT>.session(input): AgentSession<OUT>` (#1749).

## Sequence

1. First iteration runs via `sessionExec(input, emitter)`. Inner agent's events stream with its own `agentId`.
2. `next(output)` derives feedback.
3. Each subsequent iteration runs via `sessionExec(feedback, emitter)`. Events from each iteration interleave SERIALLY (loops are sequential — no concurrent iteration).
4. Loop ends when `next` returns `null` OR `maxIterations` is reached.
5. Terminal `Completed(agentId = loopAgentId ?: "loop", output = current)` fires.

## maxIterations breach

When `iterations >= maxIterations` and `next` still returns non-null, the loop throws `IllegalStateException`. Terminal event is `Failed(agentId, cause)`. `session.await()` rethrows.

## Fallback when `sessionExec` is null

A `Loop` constructed outside the factory functions has `sessionExec = null`. The session falls back to the regular `execution` — no inner events stream; only the terminal `Completed` / `Failed` fires.

## `loopAgentId` resolution

- When the loop was built via the `Agent.loop { }` extension: `loopAgentId = agent.name` (the wrapped agent).
- When built via the `Pipeline.loop { }` extension: `loopAgentId = pipeline.agents.lastOrNull()?.name`.
- When built directly (`Loop(...)`): `loopAgentId = null`, and the session uses `"loop"` as the terminal `agentId`.

## Channel + scope

Same shape as the other session extensions: `Channel.BUFFERED` + `SupervisorJob() + Dispatchers.Default`. The terminal event is published, the channel is closed, and the consumer flow completes.

## Related files

- `Loop.kt` — the type extended.
- `runtime/events/AgentSession.kt` — the returned session shape.
- `runtime/events/runAgentInSession.kt` — used by the wrapped-agent factory path.
