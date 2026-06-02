package agents_engine.model

data class ToolCall(
    val name: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val rawArguments: String? = null,
    val invalidArgumentsError: String? = null,
    /**
     * #1739 — provider-side call identifier. Set by streaming adapters
     * (Anthropic SSE `tool_use_id`, OpenAI `tool_call_id`, MCP) so the
     * agentic loop can correlate the chunks of one tool call back to a
     * single `AgentEvent.ToolCallStarted` / `ToolCallFinished` pair, even
     * under interleaved streaming. Nullable with default null — non-
     * streaming providers that don't surface an explicit id can leave
     * this empty.
     */
    val callId: String? = null,
)
