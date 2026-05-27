package agents_engine.model

import agents_engine.runtime.events.AgentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * `agents_engine/model/StreamingAggregator.kt` — the [AgentEventEmitter]
 * typealias and [chatOrStream], the single chat-or-stream entry point
 * the agentic loop calls per turn. When `emitter` is null, behaves
 * byte-for-byte as `client.chat(...)`; when non-null, collects
 * `client.chatStream(...)` while emitting `AgentEvent.Token` /
 * `ToolCallStarted` / `ToolCallArgumentsDelta` chunks, and rebuilds
 * an `LlmResponse` for the loop (#1739). See
 * `src/main/resources/internals-agent/model/StreamingAggregator.md`
 * (#1837 / #1856).
 */

// #1739 — emitter shape used to plumb AgentEvents out of the agentic
// loop. `AgentEvent<*>` because the loop only ever produces non-`OUT`
// subtypes (Token, ToolCall*, SkillStarted, SkillCompleted, Failed);
// only `AgentEvent.Completed<OUT>` carries the typed payload and that's
// emitted in `Agent.session(input)` after the loop returns.
//
// Non-suspend (#1745) so it can be called from non-suspend callbacks
// like `Agent.invokeSuspendForSession`'s `onSkillStarted` lambda.
// Implementations typically forward to `channel.trySend(event)`, which
// is itself non-blocking — appropriate for BUFFERED-channel-backed Flows.
internal typealias AgentEventEmitter = (AgentEvent<*>) -> Unit

/**
 * #1739 — round-trip the model: either via the existing non-streaming
 * `chat()` path (when [emitter] is null — byte-for-byte the old
 * behavior) or via `chatStream()` aggregated into the same `LlmResponse`
 * the agentic loop expects, emitting `AgentEvent` chunks as they arrive.
 *
 * Aggregation strategy:
 * - `TextDelta` chunks are concatenated into a final `LlmResponse.Text`.
 *   Each delta also fires an `AgentEvent.Token`.
 * - `ToolCallStarted` records `callId` -> `toolName` in arrival order.
 *   Fires `AgentEvent.ToolCallStarted`.
 * - `ToolCallArgumentsDelta` fires the matching `AgentEvent` with the
 *   same `callId` (consumers can stream JSON-arg deltas to a UI today
 *   even though the default `chatStream` impl coalesces them into one).
 * - `ToolCallFinished` (provider-side) records final arguments per
 *   `callId`. **No `AgentEvent.ToolCallFinished` fires here** — that
 *   one needs the executor's `result`, which the agentic loop produces
 *   after this function returns. The loop emits it then.
 * - `End` carries optional `tokenUsage` into the returned `LlmResponse`.
 *
 * Interleaving safety: even if a provider's native streaming adapter
 * later interleaves chunks across multiple tool calls (Anthropic SSE
 * does this), the `callId` field on each chunk routes the delta to the
 * right pending entry. `ToolCall.callId` propagates into the final
 * `LlmResponse.ToolCalls` so the loop's `ToolCallFinished` event uses
 * the same id.
 */
internal suspend fun chatOrStream(
    client: ModelClient,
    messages: List<LlmMessage>,
    agentId: String,
    skillName: String,
    jsonSchema: JsonSchema? = null,
    emitter: AgentEventEmitter?,
): LlmResponse {
    if (emitter == null) {
        return withContext(Dispatchers.IO) { client.chat(messages, jsonSchema) }
    }
    val textBuilder = StringBuilder()
    val reasoningBuilder = StringBuilder()
    val callOrder = mutableListOf<String>()
    val pendingNames = mutableMapOf<String, String>()
    val pendingArgs = mutableMapOf<String, Map<String, Any?>>()
    var tokenUsage: TokenUsage? = null

    val chunks = if (jsonSchema == null) {
        client.chatStream(messages)
    } else {
        client.chatStream(messages, jsonSchema)
    }
    chunks.collect { chunk ->
        when (chunk) {
            is LlmChunk.TextDelta -> {
                textBuilder.append(chunk.text)
                emitter(AgentEvent.Token(agentId, skillName, chunk.text))
            }
            is LlmChunk.ReasoningDelta -> {
                // #2406 — reasoning streams on its own channel, separate from the answer.
                reasoningBuilder.append(chunk.text)
                emitter(AgentEvent.Reasoning(agentId, skillName, chunk.text))
            }
            is LlmChunk.ToolCallStarted -> {
                callOrder += chunk.callId
                pendingNames[chunk.callId] = chunk.toolName
                emitter(AgentEvent.ToolCallStarted(agentId, skillName, chunk.callId, chunk.toolName))
            }
            is LlmChunk.ToolCallArgumentsDelta -> {
                emitter(AgentEvent.ToolCallArgumentsDelta(agentId, chunk.callId, chunk.deltaJson))
            }
            is LlmChunk.ToolCallFinished -> {
                // Bookkeeping only — the consumer-facing AgentEvent.ToolCallFinished
                // fires AFTER the agentic loop runs the tool executor and has a result.
                pendingArgs[chunk.callId] = chunk.arguments
            }
            is LlmChunk.End -> {
                tokenUsage = chunk.tokenUsage
            }
        }
    }

    val reasoning = reasoningBuilder.toString().ifEmpty { null }
    return if (callOrder.isNotEmpty()) {
        val calls = callOrder.map { callId ->
            ToolCall(
                name = pendingNames[callId] ?: error("LlmChunk.ToolCallStarted missing for callId=$callId"),
                arguments = pendingArgs[callId] ?: emptyMap(),
                callId = callId,
            )
        }
        LlmResponse.ToolCalls(calls, tokenUsage, reasoning)
    } else {
        LlmResponse.Text(textBuilder.toString(), tokenUsage, reasoning)
    }
}
