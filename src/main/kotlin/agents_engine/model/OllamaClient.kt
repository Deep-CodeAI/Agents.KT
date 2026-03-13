package agents_engine.model

import agents_engine.generation.LenientJsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class OllamaClient(
    private val host: String = "localhost",
    private val port: Int = 11434,
    private val model: String,
    private val temperature: Double = 0.7,
    private val tools: List<ToolDef> = emptyList(),
) : ModelClient {
    private val baseUrl = "http://$host:$port"

    private val http = HttpClient.newHttpClient()

    override fun chat(messages: List<LlmMessage>): LlmResponse {
        val body = buildRequestJson(messages)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return parseResponse(response.body())
    }

    private fun buildRequestJson(messages: List<LlmMessage>): String {
        val messagesJson = messages.joinToString(",") { msg ->
            buildString {
                append("""{"role":"${msg.role}","content":${msg.content.toJsonString()}""")
                if (!msg.toolCalls.isNullOrEmpty()) {
                    append(""","tool_calls":[""")
                    append(msg.toolCalls.joinToString(",") { tc ->
                        """{"function":{"name":"${tc.name}","arguments":${InlineToolCallParser.argsToJson(tc.arguments)}}}"""
                    })
                    append("]")
                }
                append("}")
            }
        }
        val toolsJson = if (tools.isNotEmpty()) {
            val defs = tools.joinToString(",") { t ->
                """{"type":"function","function":{"name":"${t.name}","description":${t.description.toJsonString()},"parameters":{"type":"object","properties":{}}}}"""
            }
            ""","tools":[$defs]"""
        } else ""
        return """{"model":"$model","stream":false,"temperature":$temperature,"messages":[$messagesJson]$toolsJson}"""
    }

    private fun parseResponse(body: String): LlmResponse {
        val root = LenientJsonParser.parse(body) as? Map<*, *>
            ?: return LlmResponse.Text(body)
        val message = root["message"] as? Map<*, *>
            ?: return LlmResponse.Text(body)
        val content = message["content"] as? String ?: ""

        // Native Ollama tool_calls field (models with built-in tool support)
        val rawToolCalls = message["tool_calls"] as? List<*>
        if (!rawToolCalls.isNullOrEmpty()) {
            val calls = rawToolCalls.mapNotNull { tc ->
                val fn = (tc as? Map<*, *>)?.get("function") as? Map<*, *> ?: return@mapNotNull null
                val name = fn["name"] as? String ?: return@mapNotNull null
                val rawArgs = fn["arguments"]
                val arguments = when (rawArgs) {
                    is Map<*, *> -> rawArgs.entries.associate { (k, v) -> k.toString() to v }
                    is String    -> (LenientJsonParser.parse(rawArgs) as? Map<*, *>)
                        ?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()
                    else         -> emptyMap()
                }
                ToolCall(name = name, arguments = arguments)
            }
            if (calls.isNotEmpty()) return LlmResponse.ToolCalls(calls)
        }

        // Inline JSON tool call in content (models without native tool support)
        val toolCall = InlineToolCallParser.parse(content)
        if (toolCall != null) return LlmResponse.ToolCalls(listOf(toolCall))

        return LlmResponse.Text(content)
    }
}

private fun String.toJsonString(): String =
    '"' + replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + '"'
