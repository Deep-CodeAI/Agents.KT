# Streaming agents

How to consume agent execution as a typed event stream. Pairs with the [v0.5.0 streaming premortem](premortem-0.5.0-streaming.md) for the design rationale.

## Quick start

```kotlin
import agents_engine.runtime.events.AgentEvent
import agents_engine.runtime.events.session

val session = myAgent.session(input)

session.events.collect { event ->
    when (event) {
        is AgentEvent.SkillStarted             -> log("→ ${event.skillName}")
        is AgentEvent.Token                    -> render(event.text)            // mid-loop
        is AgentEvent.Reasoning                -> renderThinking(event.text)    // mid-loop, separate channel
        is AgentEvent.ToolCallStarted          -> log("tool: ${event.toolName} (${event.callId})")
        is AgentEvent.ToolCallArgumentsDelta   -> previewArgs(event.callId, event.deltaJson)
        is AgentEvent.ToolCallFinished         -> if (event.isError) err(event) else showResult(event)
        is AgentEvent.SkillCompleted           -> log("✓ ${event.skillName} (${event.tokensUsed?.total ?: '?'} tokens)")
        is AgentEvent.Completed                -> done(event.output, event.tokensUsed)
        is AgentEvent.Failed                   -> err(event.cause)
    }
}

// OR: skip the events, just wait for the typed output.
val output: OUT = myAgent.session(input).await()
```

Each `agent.session(input)` call starts a fresh invocation. `events` is a cold `Flow<AgentEvent<OUT>>` — collecting it twice would run the agent twice. Use `events.shareIn(...)` if you need multiple collectors.

## The AgentEvent hierarchy

All subtypes carry `agentId`, `requestId`, `sessionId`, and `manifestHash`. `agentId` names the agent that produced the event; the runtime IDs let audit logs correlate every token/tool/terminal event with one invocation and, when manifests are enabled, the approved capability graph. Only `Completed` is parameterized on the agent's `OUT`; everything else is `AgentEvent<Nothing>` so events flow through any `AgentSession<OUT>`.

| Event | Fires when | Carries |
|---|---|---|
| `SkillStarted` | Before the resolved skill executes | `skillName` |
| `Token` | LLM streams a content chunk | `skillName`, `text` |
| `Reasoning` | LLM streams a reasoning/thinking chunk (opt-in via `model { reasoning(...) }`, #2406) | `skillName`, `text` |
| `ToolCallStarted` | Streaming adapter sees a new tool call | `skillName`, `callId`, `toolName` |
| `ToolCallArgumentsDelta` | Each fragment of streamed tool-call args | `callId`, `deltaJson` |
| `ToolCallFinished` | After the agentic loop runs the executor | `callId`, `toolName`, `arguments`, `result`, `isError` |
| `SkillCompleted` | Skill body has returned | `skillName`, `tokensUsed` (cumulative across all LLM turns of this skill; null for `implementedBy`) |
| `Completed<OUT>` | Terminal success — emitted exactly once | `output`, `tokensUsed` |
| `Failed` | Terminal failure — emitted exactly once before the exception propagates | `cause` |

**`implementedBy` skills:** only `SkillStarted` → `SkillCompleted` → `Completed`. No `Token` or `ToolCall*` (no LLM round-trip). `tokensUsed` is always null.

**Agentic skills (LLM-driven):** the full set fires. `Token` events arrive incrementally as the model streams (proof in `AgentSessionIncrementalArrivalTest`).

**`Completed` and `Failed` are mutually exclusive.** A session emits exactly one of them as its terminal event.

## Provider streaming status

All seven providers stream at the wire: three adapters implement `ModelClient.chatStream` natively, and the four OpenAI-compatible providers inherit `OpenAiClient`'s SSE implementation. Numbers below are from the live integration tests under `./gradlew integrationTest` against real APIs.

| Provider | Protocol | File | Live measurement (count 1–10 prompt) |
|---|---|---|---|
| Ollama | NDJSON | `OllamaClient.chatStream` | 19 chunks / 84ms gap (gpt-oss:120b-cloud) |
| Anthropic | SSE with named events + indexed content blocks | `ClaudeClient.chatStream` | 2 chunks / 27ms gap (claude-haiku-4-5) |
| OpenAI | SSE with `[DONE]` terminator | `OpenAiClient.chatStream` | 19 chunks / 202ms gap (gpt-4o-mini) |
| DeepSeek | OpenAI-compatible SSE (inherited) | `DeepSeekClient` ← `OpenAiClient.chatStream` | shared-path coverage |
| Kimi (Moonshot) | OpenAI-compatible SSE (inherited) | `KimiClient` ← `OpenAiClient.chatStream` | shared-path coverage |
| OpenRouter | OpenAI-compatible SSE (inherited) | `OpenRouterClient` ← `OpenAiClient.chatStream` | shared-path coverage |
| Perplexity | OpenAI-compatible SSE (inherited; `/chat/completions` — no `/v1` segment) | `PerplexityClient` ← `OpenAiClient.chatStream` | live-verified incl. streaming (#3675) |

Custom `ModelClient` implementations don't need to override `chatStream` — the default impl wraps `chat()` and emits one bundled chunk sequence. That's fine for non-streaming providers; it just won't show incremental arrival.

### Anthropic-specific: interleaved content blocks

Anthropic's SSE can interleave chunks across content blocks (text vs tool_use) — both have an `index` and chunks for different indices arrive mixed. `ClaudeClient.chatStream` tracks blocks in a `Map<Int, BlockState>` and routes each delta to the right block's id/builder. This is what `ToolCall.callId` was designed for; the test `ClaudeClientChatStreamTest > interleaved text and tool_use blocks emit correctly keyed by callId` pins it.

### OpenAI-specific: usage opt-in

Token usage on streamed responses requires `stream_options.include_usage: true` in the request. `OpenAiClient.buildRequestJson(stream = true)` sets it automatically; OpenAI then sends a final usage-only delta before `[DONE]`.

## TokenUsage in events

`SkillCompleted.tokensUsed` and `Completed.tokensUsed` carry a cumulative `TokenUsage` summed across every LLM turn of the skill — `promptTokens` and `completionTokens` summed independently. For a single-turn run, this equals that turn's usage; for a multi-turn loop, it's the total billed for the skill.

```kotlin
val session = agent.session(input)
val output = session.await()
session.events.toList().filterIsInstance<AgentEvent.Completed<*>>().single().tokensUsed
// → TokenUsage(promptTokens=147, completionTokens=63), or null if the provider didn't report
```

`implementedBy` skills: always null (no LLM).

## Cancellation

Contract: **cancelling the coroutine collecting `events` cancels the underlying invocation** (and so does cancelling `await()`). The session's producer runs in a detached `SupervisorJob` + `Dispatchers.Unconfined` scope; #4499 ties that scope's lifecycle to collection — the `events` flow tears the scope down in a `finally` that fires on normal completion, early completion (`take(1)`), AND external cancellation of the collector, and `await()` cancels it on its own cancellation. A suspending invocation (real model calls via `chatStream`, `delay`, any coroutine work) stops promptly instead of running to completion in the background. `SessionCancellationLeakProbeTest` pins this: a model call parked in `chatStream` observes cancellation within milliseconds.

**Fundamental limits (not a leak — no suspension point to cancel at):**

- `implementedBy` lambdas are `(IN) -> OUT` — pure synchronous code. Coroutine cancellation can only fire at suspension points, so a `Thread.sleep` or tight loop inside one isn't interrupted (the surrounding invocation is still cancelled — that synchronous step just finishes first).
- Native streaming adapters that block inside `BufferedReader.readLine()` (`HttpClient.send(BodyHandlers.ofInputStream())`) can't be interrupted mid-read by coroutine cancellation. The adapter migration to `sendAsync` (deferred) closes this.

`AgentSessionCancellationTest` pins the bare-cancellation contract (no synthetic `Failed`); `SessionCancellationLeakProbeTest` pins the teardown (#4499).

## Test coverage map

For contributors navigating the streaming test surface:

### Session API

| File | Pins |
|---|---|
| `AgentSessionBasicEventsTest` | implementedBy happy path — three ordered bracket events |
| `AgentSessionIntegrationTest` | failure path (identity-preserved cause), concurrent sessions, agentic-stub bracketing with Token, tool-call event sequence with shared callId, tokensUsed single-turn, tokensUsed cumulative across two turns |
| `AgentSessionLiveTest` | live π to 20 decimals against Ollama — `full20=true` end-to-end |
| `AgentSessionCancellationTest` | collector cancel returns under 500ms even with a 2-second sleeping skill |
| `SessionCancellationLeakProbeTest` | #4499 — collector-cancel / `take(1)` / `await()`-cancel each tear down a parked `chatStream` invocation |
| `ComplexStreamingTest` | #4499/#4500 — deep chain (head → 3-way parallel → reducer), forum stage, loop stage: no collision, `droppedEvents == 0`, single terminal |
| `AgentSessionIncrementalArrivalTest` | timing proof — first Token ≥100ms before Completed under a delayed-chunk stub |
| `ModelClientChatStreamDefaultTest` | default `chatStream` wrap of non-streaming `chat()` — Text and ToolCalls cases |

### Adapter streaming (provider-level chunk parsing)

| File | Pins |
|---|---|
| `OllamaClientChatStreamTest` | NDJSON: TextDelta sequence + End with usage; tool-call triple; empty-content skip |
| `OllamaClientChatStreamLiveTest` | live Ollama — multiple chunks with measurable timing gap |
| `ClaudeClientChatStreamTest` | SSE text-only; tool_use with `input_json_delta` accumulation; interleaved text + tool_use blocks correctly keyed by callId |
| `ClaudeClientChatStreamLiveTest` | live Anthropic — multiple chunks with usage |
| `OpenAiClientChatStreamTest` | SSE text-only with usage-only final delta; tool-call with `call_*` id reused across deltas |
| `OpenAiClientChatStreamLiveTest` | live OpenAI — multiple chunks with usage |

22 test methods across 12 files. The non-live tests run under `./gradlew test`; the live ones run under `./gradlew integrationTest` (tagged `live-llm`).

## Composition

**Composition flow-through is shipped (#3866).** *(Living demo: `CountingPipelineStreamingDemoTest` — five counting agents under `then`, 50 tokens observed live in stage order while only complete values cross the typed boundaries; it also demonstrates the `Channel.BUFFERED`/`trySend` drop semantics for zero-suspension producers; #4496 makes loss observable — `session.droppedEvents` carries the count and one summary line logs at close, replacing per-event warnings.)* Every composition operator exposes `session(input)` — `Pipeline`, `Parallel`, `Forum`, `Loop`, `Branch`, `wrap`, `Swarm` — and every `then` overload chains streaming through: a pipeline that mixes operators mid-chain (`a then (b / c)`, `(a / b) then reduce`, `head then forum`, `head then judge.loop { … }`, `head then classifier.branch { … }`) streams inner events from **all** nested agents through the parent session, each tagged with its own `agentId` for demultiplexing. Sequential stages emit in chain order; `Parallel` / `Forum` participants interleave by arrival order (by design — filter on `agentId`). Cancelling the outer `events` Flow tears down in-flight inner sessions via structured concurrency.

Concurrent legs (`Parallel` via `/`, `Forum` via `*`) demultiplex purely by `agentId` (the agent's name), so **#4500 rejects duplicate participant names at construction** — `agent("w") / agent("w")` and `a / b / a` throw with an actionable message, the same fail-loud stance as duplicate tool/skill names. (`speculative(n)` self-racing is the documented exception — those racers deliberately share one agent and its id.)

The one fallback: an operator instance constructed **outside** its factory functions (`then` / `/` / `*` / `.loop` / `.branch`) has no recorded session exec — it executes non-streaming and only its boundary events appear. **Stage boundaries are first-class (#4491):** Pipeline sessions emit `StageStarted`/`StageCompleted` pairs around each direct component (agent stages by name, operator legs labeled `parallel`/`forum`/`loop`/`branch`; nested pipelines mark their own stages exactly once) — consumers no longer infer stage transitions from `agentId` flips.

## Known gaps (current as of 0.7.24)

- *(closed by #4491: stage-boundary markers shipped — see Composition above.)*
- *(closed by #4499: cancelling collection / `await()` now cancels the underlying suspending invocation — see Cancellation above.)*
- **HTTP cancellation** mid-read — blocking InputStream isn't coroutine-cancellable.
- **Synchronous skill body cancellation** — `implementedBy` lambdas can't be interrupted (no suspension point; the surrounding invocation is still cancelled).
- **Provider-specific limits** — Ollama bundles tool-call args in one final chunk (no progressive `input_json_delta`); only Anthropic streams tool args progressively today.

See [`docs/roadmap.md`](roadmap.md) Phase 2 *Secondary* for the planned closure of each.
