package agents_engine.runtime.events

/** Tool invocation as seen by an event-log consumer — the call shape. */
data class ToolCallRecord(
    val callId: String,
    val toolName: String,
    val arguments: Map<String, Any?>,
)
