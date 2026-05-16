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
3. Build a dedicated `CoroutineScope(SupervisorJob() + Dispatchers.Default)` per session. SupervisorJob keeps the session independent of any larger scope.
4. Launch the producer coroutine: invokes `agent.invokeSuspendForSession(input, emitter, onSkillCompleted, onSkillStarted)`. The emitter forwards `AgentEvent`s via `channel.trySend`.
5. On normal completion → emit `Completed(agentId, output)`, complete the deferred, close the channel.
6. On throw → emit `Failed(agentId, cause)`, fail the deferred with the same cause, close the channel.
7. Return an `AgentSession(events = channel.consumeAsFlow(), resultDeferred = deferred)`.

## Why `BUFFERED` channel

A fast `implementedBy` skill can produce its events synchronously (SkillStarted → SkillCompleted → Completed) before the collector has even subscribed. `BUFFERED` lets those events queue without dropping. Backpressure: the channel uses default buffer size — when full, the producer suspends.

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
