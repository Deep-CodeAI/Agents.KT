---
description: Source-file knowledge for agents_engine/composition/parallel/ParallelSessionExtension.kt — parallel.session(input) (#1750). Branches launched concurrently via async; events interleave by arrival on shared channel demultiplexable by agentId. awaitAll() before terminal Completed(List<OUT>) — result order preserved. Sibling cancellation on failure. sessionExecutions=null → fall back to executions without emitter. Call when the IDE LLM needs to reason about streaming a parallel.
---

# `agents_engine/composition/parallel/ParallelSessionExtension.kt` — streaming parallel

Adds `Parallel<IN, OUT>.session(input): AgentSession<List<OUT>>` (#1750).

## Sequence

1. All branches launched concurrently via `async { sessionExecution(input, emitter) }`.
2. Each branch's events stream into the shared channel as they arrive. Order is "arrival order" — interleaved.
3. Consumers demultiplex by `AgentEvent.agentId`.
4. `awaitAll()` waits for all branches to complete.
5. Terminal `Completed(agentId = "parallel", output = List<OUT>)` fires with results in registration order.

## Failure handling

If any branch throws, sibling branches are cancelled (`coroutineScope` semantics). Terminal event is `Failed(parallelName, firstFailureCause)`. `session.await()` rethrows.

## When `sessionExecutions` is null

A `Parallel` built outside the `/` factories may have `sessionExecutions = null` — the session falls back to running `executions` without an emitter. Inner events do NOT stream; only the terminal `Completed` / `Failed` fires.

## Result-order preservation

Even though events interleave by arrival, the terminal `Completed`'s `List<OUT>` preserves registration order. This is critical for callers that index results positionally rather than by `agentId`.

## Demultiplexing example

```kotlin
parallel.session(input).events.collect { event ->
    when (event.agentId) {
        "lengthAgent" -> ui.lengthPanel(event)
        "hashAgent"   -> ui.hashPanel(event)
        "parallel"    -> if (event is Completed) ui.allDone(event.output)
    }
}
```

## Related files

- `Parallel.kt` — the type extended.
- `runtime/events/AgentSession.kt` — the returned session shape.
- `runtime/events/runAgentInSession.kt` — per-branch streaming helper.
