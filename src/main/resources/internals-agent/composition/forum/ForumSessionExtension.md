# `agents_engine/composition/forum/ForumSessionExtension.kt` — streaming forums

Adds `Forum<IN, OUT>.session(input): AgentSession<OUT>`.

## Sequence

1. **Participants run concurrently** via `async { runAgentInSession(participant, input, emitter) }.awaitAll()`. Events from all participants interleave on the shared channel; consumers demultiplex by `agentId`.
2. **Transcript assembled** — a `ForumTranscript<IN>` is built once all participants finish.
3. **Captain runs (if present)** — also via `runAgentInSession`, streaming its events with the captain's `agentId`. The transcript is its input (when `captainTakesTranscript=true`) or just the list of contributions otherwise.
4. **Terminal `Completed`** fires with the forum's effective output:
   - The captain's output when a captain is present.
   - The `ForumTranscript<IN>` itself when no captain (and `OUT == ForumTranscript<IN>`).

## Failure handling

- If any participant throws, the other participants are cancelled cooperatively (their coroutines see cancellation) and the terminal event is `Failed(forumName, cause)`.
- If the captain throws after a successful deliberation, the terminal event is `Failed(captainName, cause)`.
- `session.await()` rethrows.

## Channel + scope

`Channel.BUFFERED` + `consumeAsFlow()` for the consumer-facing flow. Producer scope is `SupervisorJob() + Dispatchers.Default` — survives one participant's failure without killing the others' event emission (the failure still terminates the deliberation but events already emitted reach the consumer).

## Demultiplexing by `agentId`

Each `AgentEvent` carries `agentId`. Consumers reconstruct per-participant timelines:

```kotlin
forum.session(input).events.collect { event ->
    when (event.agentId) {
        "reviewerA" -> showInPanelA(event)
        "reviewerB" -> showInPanelB(event)
        "captain"   -> showInCaptainPanel(event)
    }
}
```

## Related files

- `Forum.kt` — the type extended.
- `runtime/events/AgentSession.kt` — the returned session shape.
- `runtime/events/runAgentInSession.kt` — per-participant streaming.
