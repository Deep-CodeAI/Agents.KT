---
description: Source-file knowledge for agents_engine/runtime/events/AgentSessionExtension.kt — agent.session(input) entry. Builds AgentRuntimeContext (requestId + sessionId + manifestHash), Channel.BUFFERED + CompletableDeferred + dedicated SupervisorJob+Dispatchers.Default scope per session. Producer coroutine forwards AgentEvents via emitter to channel.trySend. Completed/Failed terminal events close the channel and complete/fail the deferred. Sibling session extensions for Pipeline/Branch/Loop/Forum/Parallel follow the same pattern. Call when the IDE LLM needs to reason about session plumbing internals.
---

# `agents_engine/runtime/events/AgentSessionExtension.kt` — the `agent.session(input)` entry

The extension that turns a plain `Agent` into a streaming session.

## Signature

```kotlin
fun <IN, OUT> Agent<IN, OUT>.session(input: IN): AgentSession<OUT>
```

Synchronous (not suspending) — returns immediately with an `AgentSession` whose events haven't started flowing yet.

## Internals

Per call:

1. Build `Channel<AgentEvent<OUT>>(Channel.BUFFERED)` — production decoupled from consumer pace; a fast skill can complete and queue all four events before the collector starts pulling.
2. Build `CompletableDeferred<OUT>()` for the typed result.
3. Build `AgentRuntimeContext` with a fresh `requestId`, a fresh `sessionId`, and the agent's `manifestHash` (null when no manifest exists yet).
4. Build a dedicated `CoroutineScope(SupervisorJob() + Dispatchers.Default)` per session. SupervisorJob keeps the session independent of any larger scope.
5. Launch the producer coroutine under `withAgentRuntimeContext(context)`: invokes `agent.invokeSuspendForSession(input, emitter, onSkillCompleted, onSkillStarted)`. The emitter forwards `AgentEvent`s via `channel.trySend`; a failed `trySend` (buffer full) is recorded on a `SessionDropCounter` (#4496) — not logged per event.
6. On normal completion → emit `Completed(agentId, output)`, complete the deferred, close the channel.
7. On throw → emit `Failed(agentId, cause)`, fail the deferred with the same cause, close the channel.
8. A `finally` logs one drop-summary WARNING when anything was lost (#4496).
9. Return an `AgentSession` whose `events` is `flow { try { channel.consumeAsFlow().collect { emit(it) } } finally { scope.cancel() } }` — the `finally` ties the detached producer scope to collection (#4499), so cancelling the collector (or completing early via `take(1)`) cancels the invocation instead of leaking it. The teardown lives in this flow-builder frame rather than a downstream `onCompletion` because the latter is skipped when the collecting coroutine is cancelled from outside. `await()` cancels the same scope on its own cancellation (`cancelProducer`). The `SessionDropCounter` backs the public `AgentSession.droppedEvents`.

## Why `BUFFERED` channel

A fast `implementedBy` skill can produce its events synchronously (SkillStarted → SkillCompleted → Completed) before the collector has even subscribed. `BUFFERED` lets those events queue without dropping. NO backpressure on the inner path: the emitter is a non-suspending lambda, so it uses `trySend` — when the 64-slot buffer is full, the event DROPS (counted on `AgentSession.droppedEvents` with one summary log at close, #4496). Terminal `Completed`/`Failed` use suspending `send` and never drop.

Step 3 may tune the buffer for token-streaming where TextDelta volume is high.

## Step 2 emission

For `implementedBy` skills:
```
SkillStarted(agentId, skillName)
SkillCompleted(agentId, skillName, tokenUsage=null)   // no LLM, so no usage
Completed(agentId, output)                            // typed OUT
```

For agentic skills the same three events bracket the agentic loop. `Token` and `ToolCall*` events are NOT yet surfaced — that's step 3.

## Composition session extensions

Sibling extensions live in:
- `composition/pipeline/PipelineSessionExtension.kt`
- `composition/branch/BranchSessionExtension.kt`
- `composition/loop/LoopSessionExtension.kt`
- `composition/forum/ForumSessionExtension.kt`
- `composition/parallel/ParallelSessionExtension.kt`

All follow the same pattern as this file — channel + supervisor + emitter — but compose inner agents' events per the operator's semantics.

## Related files

- `AgentSession.kt` — the returned handle.
- `AgentEvent.kt` — the event union.
- `core/Agent.kt#invokeSuspendForSession` — the streaming-aware entry point this delegates to.
- `model/StreamingAggregator.kt#AgentEventEmitter` — the typealias used to thread events through the agentic loop.
