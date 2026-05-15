# Premortem — Agents.KT v0.5.0 (streaming + suspend-first runtime)

**Status:** Draft. **Owner:** kskobeltsyn. **Date drafted:** 2026-05-15. **Target release:** 0.5.0.

Written before any v0.5.0 code lands. The actual `RELEASE_NOTES.md` will be cross-checked against this document the same way the v0.4.6 premortem was — every "we shipped streaming" claim must point at a wrapped callsite, a passing smoke test, or a ticked success-criterion box.

---

## Goal

Make `Agent<IN, OUT>` observable as a stream of typed events while preserving every existing typed-boundary guarantee.

Today: `agent.invoke(input)` blocks; `agent.invokeSuspend(input)` suspends but returns only the final `OUT`. There is no way to see the LLM's tokens, tool calls, or per-skill transitions while they happen. IDE plugins, web UIs, long-running agents, partial-cancellation, and real-time UX are all blocked on this.

After v0.5.0: `agent.session(input): AgentSession<OUT>` exposes a typed `Flow<AgentEvent<OUT>>` while running the same agentic loop underneath. Existing `invoke` / `invokeSuspend` continue to work bit-for-bit (they delegate to `session` and collect the terminal `Completed` event).

The differentiator stays the same: typed-boundary identity. The event types are sealed and typed (`AgentEvent.Completed<OUT>` carries `OUT`, not `Any?`). The session is parameterized on the agent's `OUT`. Streaming does not relax any contract — it adds visibility.

If we cannot demonstrate the contract holds under composition (`wrap`, `Branch`, `Pipeline`, `Swarm`), we do not claim "streaming runtime" — we claim "streaming entry points" and document the gap.

## Scope

In v0.5.0:

- `AgentEvent<OUT>` sealed hierarchy in `agents_engine.runtime.events.AgentEvent`.
- `AgentSession<OUT>` returned from `Agent.session(input): AgentSession<OUT>`. Fields: `events: Flow<AgentEvent<OUT>>` (cold), terminal accessor `suspend fun await(): OUT`.
- `ModelClient` gets a sibling streaming entry point — `suspend fun chatStream(messages): Flow<LlmChunk>`. The existing `fun chat(messages): LlmResponse` keeps working, with a default `chatStream` implementation that calls `chat` and emits a single non-streamed chunk (so non-streaming providers don't have to implement two methods).
- Three Ollama / Anthropic / OpenAI adapters implement `chatStream` natively (those are the three we already have).
- `executeAgentic` rewired to drive a `FlowCollector<AgentEvent<OUT>>` while preserving the existing return type. Events emit; the final return value is the source of truth for `Completed.output`.
- Cancellation: cancelling the collecting coroutine cancels the upstream HTTP call. Budget exceeded inside the loop emits `Failed(BudgetExceeded)` and closes the Flow cleanly. No leaked threads.
- LiveShow stays thread-based for now (separate refactor; out of scope here). A new `liveShowStreaming(agent)` factory wires the Flow into the existing REPL so manual testing works.
- `agents-kt-streaming-test` Gradle subproject — same pattern as `agents-kt-no-reflect-test` — pins the contract: a fake `ModelClient` that emits chunks at known cadences, assertions on event ordering, cancellation, and tool-call streaming.

Out of v0.5.0:

- Persistence / checkpointing / session resumability — v0.7.
- New providers (Google, OpenRouter, DeepSeek, Bedrock, Mistral) — v0.6, separate work.
- Spring Boot / Ktor starters — v0.6.
- RAG modules — later.
- `LiveShow` Flow-native rewrite — follow-up. Keep the thread-based REPL working.
- Multiplatform. JVM-only stays.

## The shape

```kotlin
sealed interface AgentEvent<out OUT> {
    val agentId: String  // for composition (wrap / branch / swarm) — names the source agent

    /** A token (or token group) from the LLM's text response. Not all providers stream token-by-token. */
    data class Token(override val agentId: String, val skillName: String, val text: String) : AgentEvent<Nothing>

    /** A new tool call has begun streaming. Arguments will arrive as ArgumentsDelta events; finalised in ToolCallFinished. */
    data class ToolCallStarted(override val agentId: String, val skillName: String, val callId: String, val toolName: String) : AgentEvent<Nothing>
    data class ToolCallArgumentsDelta(override val agentId: String, val callId: String, val deltaJson: String) : AgentEvent<Nothing>
    data class ToolCallFinished(override val agentId: String, val callId: String, val toolName: String, val arguments: Map<String, Any?>, val result: Any?, val isError: Boolean) : AgentEvent<Nothing>

    /** Agent has started a skill (typed-tool or implementedBy lambda). */
    data class SkillStarted(override val agentId: String, val skillName: String) : AgentEvent<Nothing>
    data class SkillCompleted(override val agentId: String, val skillName: String, val tokensUsed: TokenUsage?) : AgentEvent<Nothing>

    /** Terminal success — carries the typed output. */
    data class Completed<out OUT>(override val agentId: String, val output: OUT, val tokensUsed: TokenUsage?) : AgentEvent<OUT>
    /** Terminal failure — Flow closes cleanly with this event before the caller sees the exception. */
    data class Failed(override val agentId: String, val cause: Throwable) : AgentEvent<Nothing>
}

class AgentSession<OUT> internal constructor(
    val events: Flow<AgentEvent<OUT>>,
    private val resultDeferred: kotlinx.coroutines.Deferred<OUT>,
) {
    /** Awaits the agent's typed output. Cancels both Flow and upstream HTTP if the calling coroutine is cancelled. */
    suspend fun await(): OUT = resultDeferred.await()
}

fun <IN, OUT> Agent<IN, OUT>.session(input: IN): AgentSession<OUT> = ...
```

Notes:

- `Token` is `AgentEvent<Nothing>` (and the sealed root uses `out OUT`) so `Token` flows through any `AgentSession<OUT>` regardless of OUT — only `Completed<OUT>` carries the typed payload.
- `agentId` is required on every event. For top-level agents it's `agent.name`; for `wrap`-pipeline events from the teacher, the teacher's agent name; for `Branch`, the routed-to agent's name. No flattening that loses provenance.
- `Failed` is emitted **before** the exception propagates. Consumers collecting `events` see the terminal event and can clean up before catch.
- The Flow is **cold**: each call to `session(input)` starts a fresh agentic loop. Multiple collectors on the same session share via `events.shareIn(...)` if needed; not built in.

## ModelClient backward compat

```kotlin
fun interface ModelClient {
    fun chat(messages: List<LlmMessage>): LlmResponse

    /** Default: wraps `chat` as a single non-streamed Text chunk + tool calls. Providers override to stream natively. */
    suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> = flow {
        val response = chat(messages)
        when (response) {
            is LlmResponse.Text -> emit(LlmChunk.TextDelta(response.content)); emit(LlmChunk.End(response.tokenUsage))
            is LlmResponse.ToolCalls -> {
                response.calls.forEach { call ->
                    emit(LlmChunk.ToolCallStarted(call.callId ?: java.util.UUID.randomUUID().toString(), call.name))
                    emit(LlmChunk.ToolCallArgumentsDelta(call.callId ?: "", call.rawArguments ?: ""))
                    emit(LlmChunk.ToolCallFinished(call.callId ?: "", call.arguments))
                }
                emit(LlmChunk.End(response.tokenUsage))
            }
        }
    }
}

sealed interface LlmChunk {
    data class TextDelta(val text: String) : LlmChunk
    data class ToolCallStarted(val callId: String, val toolName: String) : LlmChunk
    data class ToolCallArgumentsDelta(val callId: String, val deltaJson: String) : LlmChunk
    data class ToolCallFinished(val callId: String, val arguments: Map<String, Any?>) : LlmChunk
    data class End(val tokenUsage: TokenUsage?) : LlmChunk
}
```

`LlmChunk` is provider-level. `AgentEvent` is consumer-level. The agentic loop reads chunks, decides what to surface as events, threads `agentId` and `skillName` in.

## Cancellation contract

1. Caller cancels the coroutine collecting `session.events`. → The agentic loop cancels its current LLM call (Ollama/Anthropic/OpenAI HTTP client honors cancellation). → Loop emits no further events; Flow closes with the cancellation exception (not a `Failed` event — the caller asked for this).
2. Caller calls `session.await()` and that coroutine is cancelled. → Same path.
3. Budget exceeded inside the loop. → Loop emits `Failed(BudgetExceededException)` → Flow closes normally. Consumers can collect-to-completion and inspect.
4. Tool executor throws. → Existing onError.* handlers run. If onError swallows: `ToolCallFinished(isError=true)` event, loop continues. If onError rethrows: `Failed`.
5. Provider HTTP error (timeout / 5xx / parse failure). → `Failed(InfrastructureException)`. No partial typed output.

`Completed` is emitted exactly once, on the success path. `Failed` is emitted exactly once, on the failure path. Never both.

## Backpressure

Use `Channel.BUFFERED` (Kotlin default for `channelFlow`). Default buffer 64; configurable via `agent { streaming { bufferSize = 256 } }` later (out of scope for 0.5.0 — start with sane default).

If consumer is slow and buffer fills, the LLM call backpressures (HTTP read pauses; provider holds the connection open up to its idle timeout). Tokens are never dropped silently. If we need a different policy (drop-oldest for high-rate token streams), it's an opt-in flag, not a default change.

## Composition fidelity

| Operator | Event behavior |
|---|---|
| `Pipeline (a then b)` | Events from `a` flow first, then events from `b`. Each tagged with that agent's id. Pipeline emits its own `SkillStarted` / `SkillCompleted` wrapping the inner events. |
| `Branch` | Routed-to agent's events flow with the routed agent's id. Branch source emits `SkillStarted(branchAgent.id)` → `SkillCompleted` around the routed sub-events. |
| `teacher wrap student` | Teacher's events appear first (id=teacher.name), then student's (id=student.name). Student's `effectivePrompt` is visible only in its own `SkillStarted` event metadata, not as a separate event. |
| `Swarm / absorb` | Sibling siblings emit interleaved events. Caller distinguishes by `agentId`. Order: first-emit wins (no synthetic ordering). |
| `Parallel` | Same as Swarm — interleaved, id-distinguishable. |
| `Forum` | All forum members' events tagged with their own ids. |

`implementedBy` skill: only `SkillStarted` → `SkillCompleted`. No `Token` events (no LLM was called). Same for `transformOutput`.

## Risks

### High

1. **Provider chunk shapes leak into AgentEvent.** Anthropic's tool-call streaming uses `input_json_delta`; OpenAI uses `arguments` delta blocks; Ollama doesn't stream tool calls at all (one-shot). If the agentic loop translates these naively, consumers see provider-specific weirdness in `AgentEvent.ToolCallArgumentsDelta`. **Mitigation:** the `LlmChunk` boundary is the translation layer. The three adapters each translate their native API into `LlmChunk`; the loop reads only `LlmChunk` and produces only `AgentEvent`. No `AgentEvent` ever carries a provider-specific field.

2. **Token-by-token event flood causes performance issues in the consumer.** A 5K-token response = 5K `Token` events. **Mitigation:** providers already chunk (Anthropic sends `content_block_delta` chunks of multiple tokens; OpenAI similar). We pass chunks through as-is; we do not split per-token. `Token.text` may carry "Hello world" (a chunk), not "H", "e", "l", "l", "o".

3. **Cancellation doesn't actually cancel the HTTP call.** If we use blocking IO inside the adapter, coroutine cancellation reaches the loop but the read keeps going. **Mitigation:** the existing adapters use `java.net.http.HttpClient` (suspendable via `sendAsync`) or `okhttp3` (cancellable via `Call.cancel()`); audit each adapter and add a smoke test that cancels mid-stream + asserts the HTTP socket closes within N ms. If any adapter can't honor cancellation, document it as a known gap (don't claim cancellation works for that adapter).

4. **Tool-call streaming is half-baked.** Some providers stream `ToolCallStarted` with the name only; some buffer until the full arguments JSON is ready; Ollama doesn't stream at all. **Mitigation:** the contract is "you'll get `ToolCallStarted` + zero-or-more `ToolCallArgumentsDelta` + exactly-one `ToolCallFinished` per call, regardless of provider." Non-streaming providers emit a single Delta with the full JSON; semantics still hold.

5. **Backward-compat break in `invoke` / `invokeSuspend`.** If we route them through `session` and Flow collection has subtle different behavior (e.g., exception wrapping), existing consumers break. **Mitigation:** golden-path test: every existing unit test passes unchanged with the new internal wiring. If a test fails, the wiring is wrong, not the test.

### Medium

6. **`Failed` event vs. thrown exception.** Two ways to signal failure (event + exception) can be confusing. **Mitigation:** document explicitly. `events` collect-to-completion sees `Failed` as the terminal event. `await()` throws. Same source — either side, never both for the same failure.

7. **`agentId` is `agent.name`, but two agents in a pipeline can share a name.** Currently `Agent.name` is unique within a single block but not globally. **Mitigation:** use a tuple `(agent.name, instance-uuid-at-session-start)` if collisions matter. v0.5.0 ships `agent.name` only and documents the limitation — collisions in composition produce ambiguous `agentId`. Tighten in 0.5.1 if it bites.

8. **Adding `session` to `Agent` is a public API change.** Risk of surface area drift if not designed conservatively. **Mitigation:** `session` is a free function `fun <IN, OUT> Agent<IN, OUT>.session(input: IN): AgentSession<OUT>` defined in a new package (`agents_engine.runtime.events`), not a method on `Agent`. The agent class stays as-is.

### Low

9. **`channelFlow` overhead.** Each session creates a channel. **Mitigation:** measured per-event overhead is ~µs; agent calls are at least milliseconds. No-op concern.

10. **`AgentEvent` evolution.** Adding a new event subtype later is a binary-compat consideration. **Mitigation:** sealed interface + `is` checks at consumer sites — adding a subtype is source-compatible only if consumers don't do exhaustive `when` without `else`. Document the recommended `when (event) { ... else -> { /* future-proof */ } }` pattern.

## Success criteria

For v0.5.0 to ship with the "streaming runtime" claim, ALL of these must be true:

- [ ] `AgentEvent` sealed hierarchy defined, with `Completed<OUT>` carrying typed output.
- [ ] `agent.session(input)` returns `AgentSession<OUT>`; `events: Flow<AgentEvent<OUT>>` cold flow; `await()` terminal.
- [ ] Existing `agent.invoke` and `agent.invokeSuspend` continue to work byte-for-byte; **every existing unit test passes unchanged**.
- [ ] All three current adapters (Ollama, Anthropic, OpenAI) implement `chatStream` natively (not via the default-wrap-chat path).
- [ ] `agents-kt-streaming-test` Gradle subproject exists with:
  - Event-ordering test: `SkillStarted → Token+ → SkillCompleted → Completed`.
  - Tool-call test: `ToolCallStarted → ArgumentsDelta+ → ToolCallFinished` with strict ordering and one-of-each guarantee.
  - Cancellation test: collecting coroutine cancellation closes the upstream stub within 100 ms; no leaked threads (verified via active count snapshot).
  - Composition test: `teacher wrap student` produces events with both agentIds in expected order; `Branch` events tag the routed-to agent.
  - `Failed` test: budget-exceeded stub returns the exception path and the Flow closes cleanly with `Failed` as terminal event.
- [ ] Documentation: README's "Limitations" section updated; PRD's streaming row marked done; new `docs/streaming.md` covering the typed-event API + cancellation + backpressure.
- [ ] No regressions in `./gradlew testAll` (root + KSP + no-reflect + integration).

If any criterion is red, the release ships as v0.5.0-alpha or v0.4.7, NOT v0.5.0. Notes say which.

## What this premortem does NOT cover

- Per-provider streaming semantics deep-dive — separate doc per adapter (`docs/streaming-ollama.md` etc.) as those land.
- The Flow-native LiveShow rewrite — separate issue, lower priority once basic streaming works.
- Persistence / resumability — v0.7 territory, needs its own premortem.
- Java-callable `chatStream` — JVM coroutines interop is awkward from Java; document as "Kotlin-only in 0.5.0," add `CompletableFuture` bridge in v0.6 if demand exists.

## Comparison checklist for post-release notes

When the actual `RELEASE_NOTES.md` for 0.5.0 is written, every "streaming" claim must point at one of:

1. A specific `AgentEvent` subtype + the file where it's defined.
2. The smoke-test subproject's name + a specific test method.
3. A specific success-criterion box from this document.
4. A specific provider adapter file that implements `chatStream` natively.

No floating claims. "Cancellation works" must point at the cancellation smoke test. "Composition preserves agentId" must point at the composition smoke test. "Backward compat holds" must point at the green existing suite.

---

*Written before the work. Will be compared against the actual outcome before the release ships.*
