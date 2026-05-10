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
 * Anthropic Messages API adapter (#1644). Mirrors [OllamaClient] in shape:
 * one shot of `chat()` per turn, `LlmMessage`/`LlmResponse` in/out, an
 * overridable [sendChat] seam for tests.
 *
 * Wire mapping:
 * - `LlmMessage("system", _)` → top-level `system` field on the request.
 * - `LlmMessage("user", text)` → `{role:"user", content:text}`.
 * - `LlmMessage("assistant", "", toolCalls=...)` → `{role:"assistant",
 *   content:[{type:"tool_use", id:"toolu_<n>", name, input}, ...]}`.
 * - `LlmMessage("tool", text)` → wrapped as `{role:"user",
 *   content:[{type:"tool_result", tool_use_id, content:text}]}`, paired in
 *   order to the most recent assistant's tool_use blocks. Synthetic ids are
 *   generated per request — [ToolCall] doesn't carry a provider id, and the
 *   ids only need to be unique within one request.
 * - Tool defs → `{name, description, input_schema}` (Anthropic's spelling;
 *   not OpenAI's `parameters`).
 *
 * Top-level `error` envelope on the response surfaces as [LlmProviderException]
 * — same boundary contract as [OllamaClient] (#702).
 */
open class ClaudeClient(
    private val apiKey: String,
    private val model: String,
    private val temperature: Double = 0.7,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val tools: List<ToolDef> = emptyList(),
    private val baseUrl: String = "https://api.anthropic.com",
    private val anthropicVersion: String = "2023-06-01",
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
            "x-api-key" to apiKey,
            "anthropic-version" to anthropicVersion,
            "content-type" to "application/json",
        )
        val responseBody = sendChat(body, headers)
        return parseResponse(responseBody)
    }

    /** Test seam — subclasses override to stub HTTP without a server. */
    internal open fun sendChat(body: String, headers: Map<String, String>): String {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/messages"))
            .timeout(requestTimeout.toJavaDuration())
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val cap = maxResponseBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes = response.body().use { it.readNBytes(cap + 1) }
        if (bytes.size > cap) {
            throw LlmProviderException(
                "Claude response exceeded $maxResponseBytes bytes; aborting to prevent OOM",
            )
        }
        return String(bytes, Charsets.UTF_8)
    }

    internal fun buildRequestJson(messages: List<LlmMessage>): String {
        val systemText = messages.firstOrNull { it.role == "system" }?.content
        val nonSystem = messages.filter { it.role != "system" }

        // Synthesize stable tool_use ids in order across the conversation — one
        // counter per request. Tool-result messages consume ids in the same
        // order (FIFO over the running queue) so the wire pairing matches.
        val pendingToolUseIds: ArrayDeque<String> = ArrayDeque()
        var toolUseCounter = 0

        val messageObjects = nonSystem.map { msg ->
            when (msg.role) {
                "user" -> """{"role":"user","content":${msg.content.toJsonString()}}"""

                "assistant" -> {
                    val blocks = mutableListOf<String>()
                    if (msg.content.isNotEmpty()) {
                        blocks += """{"type":"text","text":${msg.content.toJsonString()}}"""
                    }
                    msg.toolCalls?.forEach { call ->
                        val id = "toolu_${toolUseCounter++}"
                        pendingToolUseIds.addLast(id)
                        val argsJson = InlineToolCallParser.argsToJson(call.arguments)
                        blocks += """{"type":"tool_use","id":${id.toJsonString()},"name":${call.name.toJsonString()},"input":$argsJson}"""
                    }
                    if (blocks.isEmpty()) {
                        // Empty assistant turn: send an empty text block so the
                        // role marker is still present and well-formed.
                        blocks += """{"type":"text","text":""}"""
                    }
                    """{"role":"assistant","content":[${blocks.joinToString(",")}]}"""
                }

                "tool" -> {
                    val id = pendingToolUseIds.removeFirstOrNull()
                        ?: error("tool result with no preceding assistant tool_use to pair with")
                    """{"role":"user","content":[{"type":"tool_result","tool_use_id":${id.toJsonString()},"content":${msg.content.toJsonString()}}]}"""
                }

                else -> error("Unknown LlmMessage role for Claude: '${msg.role}'")
            }
        }

        val systemField = systemText?.let { ""","system":${it.toJsonString()}""" } ?: ""
        val toolsField = if (tools.isNotEmpty()) {
            val defs = tools.joinToString(",") { t ->
                val schema = t.argsType?.jsonSchema()
                    ?: """{"type":"object","properties":{},"additionalProperties":true}"""
                """{"name":${t.name.toJsonString()},"description":${t.description.toJsonString()},"input_schema":$schema}"""
            }
            ""","tools":[$defs]"""
        } else ""

        return """{"model":${model.toJsonString()},"max_tokens":$maxTokens,"temperature":$temperature$systemField,"messages":[${messageObjects.joinToString(",")}]$toolsField}"""
    }

    internal fun parseResponse(body: String): LlmResponse {
        val root = LenientJsonParser.parse(body) as? Map<*, *>
            ?: return LlmResponse.Text(body)

        // Provider-error envelope: Anthropic returns
        //   {"type":"error","error":{"type":"...","message":"..."}}
        // on 4xx/5xx. Surface as LlmProviderException so callers see a clean
        // boundary error instead of the raw envelope flowing into transformOutput.
        (root["error"] as? Map<*, *>)?.let { err ->
            val type = err["type"] as? String
            val message = err["message"] as? String
            throw LlmProviderException("Claude returned an error: ${type ?: "unknown"}: ${message ?: "no message"}")
        }

        val tokenUsage = extractTokenUsage(root)
        val content = root["content"] as? List<*> ?: return LlmResponse.Text(body, tokenUsage)

        val toolUses = content.mapNotNull { it as? Map<*, *> }.filter { it["type"] == "tool_use" }
        if (toolUses.isNotEmpty()) {
            val calls = toolUses.mapNotNull { tu ->
                val name = tu["name"] as? String ?: return@mapNotNull null
                val rawInput = tu["input"]
                val parsed = parseToolArguments(rawInput)
                ToolCall(
                    name = name,
                    arguments = parsed.arguments,
                    rawArguments = parsed.rawArguments,
                    invalidArgumentsError = parsed.parseError,
                )
            }
            if (calls.isNotEmpty()) return LlmResponse.ToolCalls(calls, tokenUsage)
        }

        val text = content
            .mapNotNull { it as? Map<*, *> }
            .filter { it["type"] == "text" }
            .joinToString("") { (it["text"] as? String) ?: "" }
        return LlmResponse.Text(text, tokenUsage)
    }

    private fun extractTokenUsage(root: Map<*, *>): TokenUsage? {
        val usage = root["usage"] as? Map<*, *> ?: return null
        val input = (usage["input_tokens"] as? Number)?.toInt()
        val output = (usage["output_tokens"] as? Number)?.toInt()
        return if (input != null && output != null) TokenUsage(input, output) else null
    }

    companion object {
        val DEFAULT_REQUEST_TIMEOUT: Duration = 60.seconds
        val DEFAULT_CONNECT_TIMEOUT: Duration = 10.seconds
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 16L * 1024 * 1024

        // Anthropic requires max_tokens on every Messages request. 4096 is a
        // reasonable default for chat-shaped turns; callers tuning long-form
        // generation should set their own.
        const val DEFAULT_MAX_TOKENS: Int = 4096
    }
}

private fun String.toJsonString(): String =
    '"' + replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + '"'
