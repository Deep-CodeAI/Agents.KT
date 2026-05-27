package agents_engine.model

/**
 * `agents_engine/model/LlmChunk.kt` — the provider-level streaming chunk
 * union (#1722). Each adapter's native streaming endpoint produces these;
 * non-streaming providers get the default `ModelClient.chatStream` that
 * wraps `chat()`. Stays narrow — nothing here references agentic concepts
 * like `skillName` or `agentId`. See
 * `src/main/resources/internals-agent/model/LlmChunk.md` for the adjunct
 * surfaced to IDE-side LLM tools (#1837 / #1848).
 */

// #1722 — provider-level streaming chunk type. Each adapter's native
// streaming endpoint produces these; non-streaming providers get the
// default ModelClient.chatStream which wraps `chat` and emits an
// equivalent ordered sequence.
//
// AgentEvent (consumer-level) is built on top of these in a later step.
// LlmChunk stays narrow: nothing in here references agentic concepts
// like skillName or agentId — those belong upstream.
sealed interface LlmChunk {
    /** A chunk of text from the model's response. Providers chunk at their own granularity; we pass through as-is. */
    data class TextDelta(val text: String) : LlmChunk

    /**
     * A chunk of the model's reasoning/thinking text, separate from the answer
     * [TextDelta] (#2406). Emitted (typically before the answer) when reasoning
     * is enabled and the provider exposes it (Claude thinking, DeepSeek
     * reasoning_content, Ollama thinking). Off by default.
     */
    data class ReasoningDelta(val text: String) : LlmChunk

    /** A new tool call has begun streaming. Arguments arrive next as ArgumentsDelta events; finalised in ToolCallFinished. */
    data class ToolCallStarted(val callId: String, val toolName: String) : LlmChunk

    /** Partial tool-call arguments JSON. Multiple deltas may arrive per call; consumers buffer. */
    data class ToolCallArgumentsDelta(val callId: String, val deltaJson: String) : LlmChunk

    /** Tool call fully streamed — arguments parsed into a Map. */
    data class ToolCallFinished(val callId: String, val arguments: Map<String, Any?>) : LlmChunk

    /** Terminal chunk for one chat round-trip. Carries token usage when the provider reports it. */
    data class End(val tokenUsage: TokenUsage?) : LlmChunk
}
