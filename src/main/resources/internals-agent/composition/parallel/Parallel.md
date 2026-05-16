---
description: Source-file knowledge for agents_engine/composition/parallel/Parallel.kt — concurrent fan-out via / operator. Parallel<IN,OUT> runs N branches concurrently returning List<OUT>. Same IN and OUT required. coroutineScope (#638) — caller owns scope/cancellation/dispatcher. sessionExecutions for per-branch session streaming (#1750). Sibling cancel on failure. Call when the IDE LLM needs to reason about homogeneous concurrent execution vs heterogeneous Forum.
---

# `agents_engine/composition/parallel/Parallel.kt` — concurrent fan-out

`Parallel<IN, OUT>` runs N branches concurrently against the same input. Returns `List<OUT>` (one element per branch, in registration order).

## Construction via `/`

```kotlin
val parallel: Parallel<String, Int> = lengthAgent / wordCountAgent / hashAgent
parallel(input) // List<Int> of size 3
```

All branches must share the same `IN` (else compile error on `/`) and the same `OUT`. The operator marks each agent as placed (single-placement rule).

## Shape

```kotlin
class Parallel<IN, OUT>(
    val agents: List<Agent<*, *>>,
    internal val executions: List<suspend (IN) -> OUT>,
    internal val sessionExecutions: List<suspend (IN, AgentEventEmitter) -> OUT>? = null,
)
```

- `executions` — the per-branch suspending invokers used in the non-streaming `invokeSuspend`.
- `sessionExecutions` (#1750) — per-branch session-aware invokers used when called via `parallel.session(input)`. Each branch's events stream with its own `agentId`; events interleave on the shared channel.

## Concurrency

`invokeSuspend` runs in `coroutineScope { ... }` — bounded by the caller's context. Each branch is an `async { exec(input) }`; results joined via `awaitAll()` in order. The framework does NOT create its own scope (#638) — cancellation, timeouts, dispatcher all live with the caller.

The blocking `invoke` is a one-line `runBlocking(Dispatchers.Default) { ... }` shim at the user boundary.

## Failure mode

If any branch throws, sibling branches are cancelled cooperatively (`coroutineScope` semantics). The exception propagates from the first-failed branch.

## When to use vs `Forum`

- **Parallel** — homogeneous typing, just need all results.
- **Forum** — heterogeneous participants, optionally synthesized by a captain.

## Related files

- `ParallelSessionExtension.kt` — `parallel.session(input)` streaming surface.
- `composition/forum/Forum.kt` — the heterogeneous-typed sibling.
- `Agent.kt#markPlaced` — single-placement enforcement on `/`.
