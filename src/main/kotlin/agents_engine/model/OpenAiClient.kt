package agents_engine.model

import agents_engine.generation.LenientJsonParser
import agents_engine.generation.jsonSchema
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * OpenAI Chat Completions adapter (#1656). Mirrors [OllamaClient] and
 * [ClaudeClient] in shape: one shot of `chat()` per turn, `LlmMessage` /
 * `LlmResponse` in/out, an overridable [sendChat] seam for tests.
 *
 * Wire mapping:
 * - `LlmMessage("system", text)` → `{role:"system", content:text}` — OpenAI
 *   keeps system in the messages array (unlike Anthropic's hoisted field).
 * - `LlmMessage("user", text)` → `{role:"user", content:text}`.
 * - `LlmMessage("assistant", "", toolCalls=...)` → `{role:"assistant",
 *   content:null, tool_calls:[{id:"call_<n>", type:"function",
 *   function:{name, arguments:"<stringified JSON>"}}]}`. OpenAI's wire
 *   convention puts `arguments` as a stringified JSON, not an object.
 * - `LlmMessage("tool", text)` → `{role:"tool", tool_call_id:"call_<n>",
 *   content:text}` paired in order to the most recent assistant's tool_calls.
 *   Synthetic ids are generated per request — [ToolCall] doesn't carry a
 *   provider id, and ids only need to be unique within one request.
 * - Tool defs → `[{type:"function", function:{name, description, parameters}}]`
 *   — OpenAI's `parameters`, not Anthropic's `input_schema`.
 *
 * Top-level `error` envelope on the response surfaces as [LlmProviderException]
 * — same boundary contract as [OllamaClient] (#702).
 */
open class OpenAiClient(
    private val apiKey: String,
    private val model: String,
    private val temperature: Double = 0.7,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val tools: List<ToolDef> = emptyList(),
    private val baseUrl: String = "https://api.openai.com",
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    private val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) : ModelClient {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout.toJavaDuration())
        .build()

    override fun chat(messages: List<LlmMessage>): LlmResponse {
        val body = buildRequestJson(messages)
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "content-type" to "application/json",
        )
        val responseBody = sendChat(body, headers)
        return parseResponse(responseBody)
    }

    /** Test seam — subclasses override to stub HTTP without a server. */
    internal open fun sendChat(body: String, headers: Map<String, String>): String {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/chat/completions"))
            .timeout(requestTimeout.toJavaDuration())
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val cap = maxResponseBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes = response.body().use { it.readNBytes(cap + 1) }
        if (bytes.size > cap) {
            throw LlmProviderException(
                "OpenAI response exceeded $maxResponseBytes bytes; aborting to prevent OOM",
            )
        }
        return String(bytes, Charsets.UTF_8)
    }

    internal fun buildRequestJson(messages: List<LlmMessage>): String {
        val pendingToolCallIds: ArrayDeque<String> = ArrayDeque()
        var toolCallCounter = 0

        val messageObjects = messages.map { msg ->
            when (msg.role) {
                "system", "user" ->
                    """{"role":${msg.role.toJsonString()},"content":${msg.content.toJsonString()}}"""

                "assistant" -> {
                    val toolCallsJson = msg.toolCalls?.joinToString(",") { call ->
                        val id = "call_${toolCallCounter++}"
                        pendingToolCallIds.addLast(id)
                        val argsString = InlineToolCallParser.argsToJson(call.arguments)
                        """{"id":${id.toJsonString()},"type":"function","function":{"name":${call.name.toJsonString()},"arguments":${argsString.toJsonString()}}}"""
                    }
                    val contentJson = if (msg.content.isEmpty()) "null" else msg.content.toJsonString()
                    if (toolCallsJson.isNullOrBlank()) {
                        """{"role":"assistant","content":$contentJson}"""
                    } else {
                        """{"role":"assistant","content":$contentJson,"tool_calls":[$toolCallsJson]}"""
                    }
                }

                "tool" -> {
                    val id = pendingToolCallIds.removeFirstOrNull()
                        ?: error("tool result with no preceding assistant tool_calls to pair with")
                    """{"role":"tool","tool_call_id":${id.toJsonString()},"content":${msg.content.toJsonString()}}"""
                }

                else -> error("Unknown LlmMessage role for OpenAI: '${msg.role}'")
            }
        }

        val toolsField = if (tools.isNotEmpty()) {
            val defs = tools.joinToString(",") { t ->
                val schema = t.argsType?.jsonSchema()
                    ?: """{"type":"object","properties":{},"additionalProperties":true}"""
                """{"type":"function","function":{"name":${t.name.toJsonString()},"description":${t.description.toJsonString()},"parameters":$schema}}"""
            }
            ""","tools":[$defs]"""
        } else ""

        return """{"model":${model.toJsonString()},"max_tokens":$maxTokens,"temperature":$temperature,"messages":[${messageObjects.joinToString(",")}]$toolsField}"""
    }

    internal fun parseResponse(body: String): LlmResponse {
        val root = LenientJsonParser.parse(body) as? Map<*, *>
            ?: return LlmResponse.Text(body)

        // Provider-error envelope: OpenAI returns
        //   {"error":{"type":"...","message":"...","code":"..."}}
        // on 4xx/5xx. Surface as LlmProviderException — same contract as
        // OllamaClient (#702) and ClaudeClient (#1644).
        (root["error"] as? Map<*, *>)?.let { err ->
            val type = err["type"] as? String
            val message = err["message"] as? String
            throw LlmProviderException("OpenAI returned an error: ${type ?: "unknown"}: ${message ?: "no message"}")
        }

        val tokenUsage = extractTokenUsage(root)
        val choices = root["choices"] as? List<*> ?: return LlmResponse.Text(body, tokenUsage)
        val choice = choices.firstOrNull() as? Map<*, *> ?: return LlmResponse.Text(body, tokenUsage)
        val message = choice["message"] as? Map<*, *> ?: return LlmResponse.Text(body, tokenUsage)

        val rawToolCalls = message["tool_calls"] as? List<*>
        if (!rawToolCalls.isNullOrEmpty()) {
            val calls = rawToolCalls.mapNotNull { tc ->
                val tcMap = tc as? Map<*, *> ?: return@mapNotNull null
                val function = tcMap["function"] as? Map<*, *> ?: return@mapNotNull null
                val name = function["name"] as? String ?: return@mapNotNull null
                // OpenAI sends arguments as a stringified JSON; reuse the
                // shared lenient parser via parseToolArguments.
                val parsed = parseToolArguments(function["arguments"])
                ToolCall(
                    name = name,
                    arguments = parsed.arguments,
                    rawArguments = parsed.rawArguments,
                    invalidArgumentsError = parsed.parseError,
                )
            }
            if (calls.isNotEmpty()) return LlmResponse.ToolCalls(calls, tokenUsage)
        }

        val content = message["content"] as? String ?: ""
        return LlmResponse.Text(content, tokenUsage)
    }

    private fun extractTokenUsage(root: Map<*, *>): TokenUsage? {
        val usage = root["usage"] as? Map<*, *> ?: return null
        val prompt = (usage["prompt_tokens"] as? Number)?.toInt()
        val completion = (usage["completion_tokens"] as? Number)?.toInt()
        return if (prompt != null && completion != null) TokenUsage(prompt, completion) else null
    }

    companion object {
        val DEFAULT_REQUEST_TIMEOUT: Duration = 60.seconds
        val DEFAULT_CONNECT_TIMEOUT: Duration = 10.seconds
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 16L * 1024 * 1024
        const val DEFAULT_MAX_TOKENS: Int = 4096
    }
}

private fun String.toJsonString(): String =
    '"' + replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + '"'
