package agents_engine.model

import agents_engine.generation.LenientJsonParser
import agents_engine.generation.jsonSchema
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal data class ParsedToolArguments(
    val arguments: Map<String, Any?>,
    val rawArguments: String? = null,
    val parseError: String? = null,
)

internal fun parseToolArguments(rawArgs: Any?): ParsedToolArguments = when (rawArgs) {
    null -> ParsedToolArguments(emptyMap())
    is Map<*, *> -> ParsedToolArguments(
        arguments = rawArgs.entries.associate { (k, v) -> k.toString() to v },
    )
    is String -> {
        val trimmed = rawArgs.trim()
        if (trimmed.isEmpty()) {
            ParsedToolArguments(emptyMap(), rawArguments = rawArgs)
        } else {
            val parsed = LenientJsonParser.parse(rawArgs)
            when (parsed) {
                is Map<*, *> -> ParsedToolArguments(
                    arguments = parsed.entries.associate { (k, v) -> k.toString() to v },
                    rawArguments = rawArgs,
                )
                null -> ParsedToolArguments(
                    arguments = emptyMap(),
                    rawArguments = rawArgs,
                    parseError = "Could not parse tool arguments as JSON object.",
                )
                else -> ParsedToolArguments(
                    arguments = emptyMap(),
                    rawArguments = rawArgs,
                    parseError = "Tool arguments must decode to a JSON object.",
                )
            }
        }
    }
    else -> ParsedToolArguments(
        arguments = emptyMap(),
        rawArguments = rawArgs.toString(),
        parseError = "Tool arguments must be provided as a JSON object.",
    )
}

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

    internal fun buildRequestJson(messages: List<LlmMessage>): String {
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
                val parametersJson = t.argsType?.jsonSchema()
                    ?: """{"type":"object","properties":{},"additionalProperties":true}"""
                """{"type":"function","function":{"name":"${t.name}","description":${t.description.toJsonString()},"parameters":$parametersJson}}"""
            }
            ""","tools":[$defs]"""
        } else ""
        return """{"model":"$model","stream":false,"temperature":$temperature,"messages":[$messagesJson]$toolsJson}"""
    }

    internal fun parseResponse(body: String): LlmResponse {
        val root = LenientJsonParser.parse(body) as? Map<*, *>
            ?: return LlmResponse.Text(body)
        // Provider-error envelope (#702): when Ollama rejects the request — capability
        // mismatch, model-not-found, malformed input — it responds with a top-level
        // {"error":"..."} shape. Surface as LlmProviderException so the caller sees a
        // clean provider-boundary error instead of letting the JSON flow into the
        // user's transformOutput as if it were model output.
        (root["error"] as? String)?.let { errorText ->
            throw LlmProviderException("Ollama returned an error: $errorText")
        }
        val message = root["message"] as? Map<*, *>
            ?: return LlmResponse.Text(body)
        val content = message["content"] as? String ?: ""

        // Native Ollama tool_calls field (models with built-in tool support)
        val rawToolCalls = message["tool_calls"] as? List<*>
        if (!rawToolCalls.isNullOrEmpty()) {
            val calls = rawToolCalls.mapNotNull { tc ->
                val fn = (tc as? Map<*, *>)?.get("function") as? Map<*, *> ?: return@mapNotNull null
                val name = fn["name"] as? String ?: return@mapNotNull null
                val parsedArgs = parseToolArguments(fn["arguments"])
                ToolCall(
                    name = name,
                    arguments = parsedArgs.arguments,
                    rawArguments = parsedArgs.rawArguments,
                    invalidArgumentsError = parsedArgs.parseError,
                )
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
