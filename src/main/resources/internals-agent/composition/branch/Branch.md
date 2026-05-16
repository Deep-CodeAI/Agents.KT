---
description: Source-file knowledge for agents_engine/composition/branch/Branch.kt — the routing operator. Branch<IN, OUT> runs a source agent then dispatches on result type/null/else to a registered route. Order matters — first matching route wins. Suspend executors (#638) compose with agents/pipelines. Session-aware sessionExecutor + routedAgentName (#1748). Call when the IDE LLM needs to reason about type-dispatch routing.
---

# `agents_engine/composition/branch/Branch.kt` — the routing operator

`Branch<IN, OUT>` runs a source agent and dispatches on the result to a registered route. First matching route wins (registration order matters).

## Route types

| Variant | Matches when |
|---|---|
| `TypeRoute(klass)` | `klass.isInstance(result)` — covers subtypes. Place `on<Dog>()` before `on<Animal>()`. |
| `NullRoute` | Result is `null`. |
| `ElseRoute` | Anything not handled by prior routes. |

## Suspend executors (#638)

`executor: suspend (Any?) -> OUT` — routes dispatch into agents/pipelines via their suspending entry points without nested `runBlocking`. The blocking `Branch.invoke` is a one-line shim wrapping `runBlocking` exactly once at the user boundary.

## Session-aware (#1748)

Each route can carry an optional `sessionExecutor: suspend (Any?, AgentEventEmitter) -> OUT` and a `routedAgentName: String?` populated by `BranchBuilder`. When `branch.session(input)` runs and the route matches, the executor streams the routed agent's inner events into the channel. Routes built outside `BranchBuilder` (no `sessionExecutor`) fall back to the regular `executor` — events from the routed agent won't stream, but the terminal `Completed`/`Failed` still fires.

## Construction

Always built via `BranchBuilder` from the source agent:

```kotlin
val branch = sourceAgent.branch<String> {
    on<Cat>() then catHandler
    on<Dog>() then dogHandler
    onNull() then noResultHandler
    orElse() then catchAllPipeline
}
```

## Related files

- `BranchBuilder.kt` — the DSL surface that produces routes.
- `BranchSessionExtension.kt` — the `branch.session(input)` extension.
- `Pipeline.kt`, `Agent.kt` — accepted targets in `then`.
- `runtime/events/AgentEvent.kt` — events emitted under session.
