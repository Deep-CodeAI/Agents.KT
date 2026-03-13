package agents_engine.model

data class LlmMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCall>? = null,
)

data class ToolCall(val name: String, val arguments: Map<String, Any?>)

sealed interface LlmResponse {
    data class Text(val content: String) : LlmResponse
    data class ToolCalls(val calls: List<ToolCall>) : LlmResponse
}

fun interface ModelClient {
    fun chat(messages: List<LlmMessage>): LlmResponse
}
