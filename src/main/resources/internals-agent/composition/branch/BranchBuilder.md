---
description: Source-file knowledge for agents_engine/composition/branch/BranchBuilder.kt — the Branch DSL. on<T>() then agent / then pipeline, onNull(), orElse(). Each then marks the target placed (single-placement) and wires sessionExecutor (#1748) via runAgentInSession or pipeline.effectiveSessionExec. ReflectionFallback for the cast lambda. Call when the IDE LLM needs to reason about how Branch routes are assembled.
---

# `agents_engine/composition/branch/BranchBuilder.kt` — the Branch DSL

The builder behind `sourceAgent.branch<OUT> { ... }`.

## DSL

```kotlin
val branch: Branch<IN, OUT> = sourceAgent.branch<OUT> {
    on<Cat>() then catHandler             // TypeRoute<Cat>
    on<Dog>() then dogPipeline            // TypeRoute<Dog>, accepts Pipeline too
    onNull() then noResultHandler         // NullRoute
    orElse() then catchAllHandler         // ElseRoute
}
```

Each `then` registers a `BranchRoute` and **marks the target agent as placed** (single-placement rule). Calling `then` with an already-placed agent throws.

## `OnClause<T>`

`on<T>()` returns an `OnClause<T>` with two `infix then` overloads:

- `then(agent: Agent<T, OUT>)` — registers a `TypeRoute` whose `executor` calls `agent.invokeSuspend(castFn(input))`. The `sessionExecutor` (#1748) streams the agent's events through `runAgentInSession`.
- `then(pipeline: Pipeline<T, OUT>)` — same shape but calls `pipeline.invokeSuspend(...)` and `pipeline.effectiveSessionExec(...)` for the session path. `routedAgentName` is the last agent's name (whose output is the pipeline's output).

## Casting

`OnClause` carries `castFn: (Any?) -> T` so the agentic-loop runtime can pass the raw source-agent output through safely. Built via `ReflectionFallback.withReflection { value as T }` so the runtime stays graceful even when reflection isn't available.

## Routes vs ad-hoc construction

A `Branch` can be hand-constructed by passing a `List<BranchRoute>` directly — useful for tests. Hand-constructed routes lack `sessionExecutor` / `routedAgentName`; their `branch.session` falls back gracefully but doesn't stream inner events.

## Related files

- `Branch.kt` — what gets built.
- `BranchSessionExtension.kt` — consumer of `sessionExecutor` / `routedAgentName`.
- `Pipeline.kt` — accepted target in `then`.
- `Agent.kt#markPlaced` — single-placement enforcement.
