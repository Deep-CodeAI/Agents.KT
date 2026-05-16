# `agents_engine/composition/loop/Loop.kt` — feedback-loop operator

`Loop<IN, OUT>` runs an execution repeatedly, feeding each output back through a `next` function to derive the next input. Terminates when `next` returns `null` or `maxIterations` (default 1000) is hit.

## Shape

```kotlin
class Loop<IN, OUT>(
    internal val execution: suspend (IN) -> OUT,
    internal val next: (OUT) -> IN?,
    internal val maxIterations: Int = 1_000,
    internal val sessionExec: (suspend (IN, AgentEventEmitter) -> OUT)? = null,
    internal val loopAgentId: String? = null,
)
```

- `execution(input)` — one iteration. Suspend so it composes with other operators (#638).
- `next(output): IN?` — derives the next input. Returns `null` to stop and surface the current output as the loop's `OUT`. Sync — feedback functions are pure logic.
- `maxIterations` — hard cap. `require(maxIterations > 0)` at construction. Loop exits with `IllegalStateException` if hit.
- `sessionExec` (#1749) — session-aware execution path. Each iteration's wrapped agent streams events with its own `agentId`.
- `loopAgentId` — `agentId` for the terminal `Completed` event from `loop.session(input)`.

## Iteration

```kotlin
var current = execution(input)
var iterations = 1
while (true) {
    val feedback = next(current) ?: return current
    check(iterations < maxIterations) { "Loop exceeded maxIterations=$maxIterations" }
    current = execution(feedback)
    iterations++
}
```

The first iteration runs unconditionally; subsequent ones only run when `next` returns non-null.

## DSL

```kotlin
val refineLoop = drafter.loop { output ->
    if (output.score > 0.9) null            // good enough — stop
    else output.input                       // try again with the same input
}
```

The `loop { feedback }` extension on `Agent<IN, OUT>` is the common case — `execution` becomes the agent's `invokeSuspend`.

## Use cases

- **Refinement** — model writes a draft, scoring stage decides "good enough" or "try again".
- **Until-fixed-point** — apply a transformation repeatedly until output equals input.
- **Bounded retry** — wrap a flaky operation with a retry policy in `next`.

## Related files

- `LoopSessionExtension.kt` — `loop.session(input)` streaming surface.
- `Agent.kt`, `Pipeline.kt` — the wrapped types.
- `composition/pipeline/Pipeline.kt` — `then` overloads that accept a `Loop`.
