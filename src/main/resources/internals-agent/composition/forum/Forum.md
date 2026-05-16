---
description: Source-file knowledge for agents_engine/composition/forum/Forum.kt — the deliberation operator. Forum<IN,OUT> fans input out to N heterogeneous Agent<IN,*> participants concurrently, collects as ForumTranscript<IN>, optional captain synthesizes final OUT. ParticipantContribution(agentName, output: Any?). @Mention text routing via onMentionEmitted. coroutineScope concurrency (#638). Call when the IDE LLM needs to reason about multi-agent voting/debate/ensemble.
---

# `agents_engine/composition/forum/Forum.kt` — the deliberation operator

`Forum<IN, OUT>` fans the same input out to N heterogeneous participants in parallel, collects their outputs, and either:
- Returns the transcript directly (when `OUT == ForumTranscript<IN>`), OR
- Hands the transcript to a "captain" agent that synthesizes a final `OUT`.

## Shape

```kotlin
data class ParticipantContribution(val agentName: String, val output: Any?)

data class ForumTranscript<IN>(
    val originalInput: IN,
    val contributions: List<ParticipantContribution>,
)

class Forum<IN, OUT>(
    val agents: List<Agent<IN, *>>,
    internal val outType: KClass<*>,
    internal val castOut: (Any?) -> OUT,
    internal val captainTakesTranscript: Boolean = false,
)
```

Participants are `Agent<IN, *>` — heterogeneously typed outputs. Their results are erased to `Any?` in `ParticipantContribution`.

## Use cases

- **N-of-many voting** — three reviewers + a captain that votes by majority.
- **Multi-agent debate** — proposer + critic + judge.
- **Ensemble reasoning** — multiple specialists working the same problem; captain synthesizes.

## Captain

When a captain agent is wired in, it consumes a `ForumTranscript<IN>` (when `captainTakesTranscript=true`) or just the list of contributions (when false), then produces the final `OUT`. The captain runs AFTER all participants complete — sequential, not concurrent with the deliberation.

## `@Mention` observability

`onMentionEmitted { agentName, output -> ... }` fires when any participant emits a `@AgentName` reference in its output (the framework parses this from text). Useful for surfacing inter-agent references for debugging or visualization.

## Concurrency

Participants run via `async { agent.invokeSuspend(input) }.awaitAll()` inside `coroutineScope` — bounded by the caller's coroutine context (cancellation, dispatcher). The blocking `invoke` is a one-line `runBlocking` shim at the user boundary (#638).

## `castOut` and `outType`

Internal `castOut: (Any?) -> OUT` lets the framework safely coerce the captain's output back to the typed `OUT` even when the captain's static type doesn't match. `outType: KClass<*>` records the expected output class for runtime checks.

## Related files

- `ForumSessionExtension.kt` — `forum.session(input)` streaming surface.
- `Agent.kt` — participant + captain type.
- `composition/pipeline/Pipeline.kt` — `forum then nextStage` chaining.
