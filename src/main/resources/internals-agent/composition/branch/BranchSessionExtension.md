---
description: Source-file knowledge for agents_engine/composition/branch/BranchSessionExtension.kt — branch.session(input) (#1748). Source agent streams first (agentId=source.name), matched route streams with routedAgentName, terminal Completed uses routedAgentName. Routes built outside BranchBuilder fall back gracefully. Channel.BUFFERED + SupervisorJob + Dispatchers.Default. Call when the IDE LLM needs to reason about streaming a branch.
---

# `agents_engine/composition/branch/BranchSessionExtension.kt` — streaming branches

Adds `Branch<IN, OUT>.session(input): AgentSession<OUT>` (#1748). The session surface for `Branch`.

## Sequence

1. Source agent runs first via `runAgentInSession(source, input, emitter)` — its events stream with `agentId = source.name`.
2. The result is matched against routes in registration order. First match wins.
3. The matched route's `sessionExecutor(value, emitter)` runs (or falls back to `executor(value)` when null). The routed agent's events stream with their own `agentId`.
4. Terminal `Completed` fires carrying:
   - `agentId = route.routedAgentName ?: source.name`.
   - `output = route's typed result`.

## Failure handling

If either the source or the routed agent throws:
- Terminal event is `Failed(agentId, cause)` carrying the original throwable.
- `session.await()` rethrows.

## Routes built outside `BranchBuilder`

When a route's `sessionExecutor` is null:
- The fallback path calls the non-streaming `executor`.
- Inner events from the routed agent do NOT stream (no emitter to forward to).
- The terminal `Completed` / `Failed` still fires correctly.

This affordance keeps hand-constructed `Branch` instances usable under sessions without requiring session-wiring at construction time.

## Channel + coroutine scope

The session uses a `Channel.BUFFERED` consumed via `consumeAsFlow()`. The producer coroutine runs on a `SupervisorJob() + Dispatchers.Default` scope so a producer failure doesn't cancel sibling consumers. The `CompletableDeferred<OUT>` carries the final result for `session.await()`.

## Related files

- `Branch.kt` — the type extended.
- `BranchBuilder.kt` — populates `sessionExecutor` and `routedAgentName`.
- `runtime/events/AgentSession.kt` — the returned session shape.
- `runtime/events/runAgentInSession.kt` — the helper that streams a single agent.
