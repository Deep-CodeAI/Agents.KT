# `agents_engine/runtime/events/AgentSession.kt` — the session handle

The return type of `Agent.session(input)`.

## Shape

```kotlin
class AgentSession<OUT> internal constructor(
    val events: Flow<AgentEvent<OUT>>,
    private val resultDeferred: Deferred<OUT>,
) {
    suspend fun await(): OUT = resultDeferred.await()
}
```

Two surfaces:
- `events: Flow<AgentEvent<OUT>>` — cold flow of all session events, terminated by `Completed` or `Failed`.
- `await(): OUT` — suspending function returning the typed output (or rethrowing the original exception).

## Cold flow semantics

Each call to `agent.session(...)` starts a FRESH invocation, regardless of whether you've collected from a previous session's `events`. The flow IS cold. To share one invocation's events across multiple collectors:

```kotlin
val session = agent.session(input)
val shared = session.events.shareIn(coroutineScope, SharingStarted.Eagerly)
shared.collect { uiPanelA.show(it) }     // collector 1
shared.collect { uiPanelB.show(it) }     // collector 2 — same events, no re-invocation
```

## Cancellation

Cancellation propagates both ways:
- Cancelling the coroutine collecting `events` cancels the agent invocation.
- Cancelling the coroutine calling `await()` cancels the agent invocation.
- Either path propagates into the upstream LLM HTTP call (step 3 hardens this once native streaming adapters land).

The propagation works because the underlying coroutine scope's job is structured under the calling coroutine. When that's cancelled, the framework's launched producer cancels too.

## Throw semantics on `await()`

`await()` throws the ORIGINAL exception (NOT wrapped). The corresponding `AgentEvent.Failed(cause = same throwable)` event still appears in `events` as the terminal element — same identity for both surfaces.

## `internal` constructor

`AgentSession` can only be constructed by the framework. Users get instances from `agent.session(...)` (and `pipeline.session(...)`, etc., per composition extension).

## Related files

- `AgentEvent.kt` — the event union flowing through `events`.
- `AgentSessionExtension.kt` — the `Agent.session(input)` entry point.
- Composition session extensions — sibling extension functions on `Pipeline`, `Branch`, `Loop`, `Forum`, `Parallel`.
