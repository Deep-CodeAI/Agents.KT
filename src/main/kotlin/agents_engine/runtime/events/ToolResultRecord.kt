package agents_engine.runtime.events

/** Tool outcome as seen by an event-log consumer — the result shape. */
data class ToolResultRecord(
    val callId: String,
    val toolName: String,
    val result: Any?,
    val isError: Boolean,
)
