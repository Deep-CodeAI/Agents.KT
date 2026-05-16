# `agents_engine/composition/pipeline/Pipeline.kt` — sequential composition

The `then` infix produces a `Pipeline<IN, OUT>` that runs one agent's output into the next.

## Construction

```kotlin
val pipe: Pipeline<String, Answer> = parseAgent then planAgent then solveAgent then formatAgent
val result: Answer = pipe(input)
```

Each `then` overload accepts the next stage: `Agent`, `Pipeline`, `Forum`, `Loop`, `Parallel`, `Branch`. Many overloads exist — types are statically checked: `Agent<A,B> then Agent<B,C>` works; `Agent<A,B> then Agent<X,C>` fails to compile.

## Shape

```kotlin
class Pipeline<IN, OUT>(
    val agents: List<Agent<*, *>>,
    internal val sessionExec: (suspend (IN, AgentEventEmitter) -> OUT)? = null,
    private val execution: suspend (IN) -> OUT,
)
```

- `agents` — flat list of every agent in the chain (across nested pipelines). Used for introspection and the single-placement check.
- `execution: suspend (IN) -> OUT` — the one-shot runner. Suspend so internal cross-calls (Pipeline ↔ Forum ↔ Parallel ↔ Loop ↔ Branch) chain in one coroutine without nested `runBlocking` (#638).
- `sessionExec` (#1745) — session-aware runner. Streams inner-agent events with their own `agentId`s when invoked via `pipeline.session(input)`.
- `effectiveSessionExec` — public-internal: explicit `sessionExec` when supplied, otherwise a thunk that runs `execution(input)` ignoring the emitter (terminal events only — known gap, see #1745 follow-ups).

## Trailing-lambda ordering

`sessionExec` is declared BEFORE `execution` so the trailing-lambda construction `Pipeline(agents) { input -> ... }` still binds the lambda to `execution`. Important — adding `sessionExec` after `execution` would silently rebind every existing call site to the wrong parameter.

## The blocking shim

`operator fun invoke(input: IN): OUT = runBlocking { invokeSuspend(input) }` — runs `runBlocking` exactly once at the user-visible call boundary. Internal chains never re-enter `runBlocking`.

## Single-placement

Each `then` calls `markPlaced("pipeline")` on the stage. A second placement throws `IllegalArgumentException`. The pipeline itself can be placed once into a parent structure.

## Related files

- `PipelineSessionExtension.kt` — `pipeline.session(input)` streaming surface.
- `Branch.kt`, `Forum.kt`, `Loop.kt`, `Parallel.kt` — accepted targets in `then`.
- `Agent.kt#markPlaced` — single-placement enforcement.
- `composition/wrap/Wrap.kt` — `teacher wrap student` also produces a `Pipeline`.
