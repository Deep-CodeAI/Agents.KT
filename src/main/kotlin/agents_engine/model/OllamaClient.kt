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

open class OllamaClient(
    private val host: String = "localhost",
    private val port: Int = 11434,
    private val model: String,
    private val temperature: Double = 0.7,
    private val tools: List<ToolDef> = emptyList(),
    /** Per-request wall-clock cap. See #852. */
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    /** TCP connect timeout for the underlying HttpClient. See #852. */
    private val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    /** Hard cap on response body size — anything bigger throws. See #853. */
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) : ModelClient {
    private val baseUrl = "http://$host:$port"

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout.toJavaDuration())
        .build()

    /**
     * #706: Once a model has been observed to reject native tools, skip the native
     * attempt on subsequent calls and go straight to the inline-prompt path. This
     * matters for the agentic loop, which calls `chat()` multiple times per turn —
     * without the latch we'd burn an extra HTTP roundtrip per turn re-discovering
     * the same incapability.
     */
    @Volatile private var nativeToolsKnownUnsupported: Boolean = false

    override fun chat(messages: List<LlmMessage>): LlmResponse {
        if (tools.isNotEmpty() && nativeToolsKnownUnsupported) {
            return parseResponse(sendChat(buildRequestJson(withInlineToolPrompt(messages), includeTools = false)))
        }
        val body = buildRequestJson(messages, includeTools = true)
        val responseBody = sendChat(body)
        return try {
            parseResponse(responseBody)
        } catch (e: LlmProviderException) {
            // #706: Some Ollama models (e.g. gemma3) reject native `tools` capability.
            // Instead of failing, retry once with tools removed from the request and
            // the tool catalog injected into a system message — the inline JSON tool
            // call format that `InlineToolCallParser` already consumes. Other provider
            // errors (auth, model-not-found, transport) propagate unchanged.
            if (tools.isNotEmpty() && isNativeToolCapabilityError(e.message)) {
                nativeToolsKnownUnsupported = true
                val inlineMessages = withInlineToolPrompt(messages)
                val inlineBody = buildRequestJson(inlineMessages, includeTools = false)
                parseResponse(sendChat(inlineBody))
            } else {
                throw e
            }
        }
    }

    /** Test seam (#706): subclasses override to stub HTTP without standing up a server. */
    internal open fun sendChat(body: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/chat"))
            .header("Content-Type", "application/json")
            .timeout(requestTimeout.toJavaDuration())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        // #853 — bounded read so a malicious or buggy upstream can't OOM us.
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val cap = maxResponseBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes = response.body().use { it.readNBytes(cap + 1) }
        if (bytes.size > cap) {
            throw LlmProviderException(
                "Ollama response exceeded $maxResponseBytes bytes; aborting to prevent OOM",
            )
        }
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        // 60s — chat completions can be slow; large enough not to false-trip on
        // legitimate long responses, small enough to bound a hung Ollama instance.
        // See #852.
        val DEFAULT_REQUEST_TIMEOUT: Duration = 60.seconds

        // 10s — TCP connect should never take this long on a healthy network.
        val DEFAULT_CONNECT_TIMEOUT: Duration = 10.seconds

        // 16 MiB — LLM responses can be large but not THAT large; cap keeps OOM
        // off the table when the upstream is malicious or buggy. See #853.
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 16L * 1024 * 1024
    }

    private fun isNativeToolCapabilityError(msg: String?): Boolean =
        msg?.contains("does not support tools", ignoreCase = true) == true

    internal fun buildInlineToolPrompt(): String {
        val descriptions = tools.joinToString("\n") { t ->
            val params = t.argsType?.jsonSchema() ?: """{"type":"object"}"""
            "- ${t.name}: ${t.description}. Arguments schema: $params"
        }
        return """
            You can use the following tools. To call a tool, respond with ONLY a single JSON object — no prose, no code fences, no explanation:

            {"tool":"<tool_name>","arguments":{<key>:<value>, ...}}

            If no tool is needed, answer normally in plain text.

            Available tools:
            $descriptions
        """.trimIndent()
    }

    internal fun withInlineToolPrompt(messages: List<LlmMessage>): List<LlmMessage> {
        val inlinePrompt = buildInlineToolPrompt()
        val first = messages.firstOrNull()
        return if (first?.role == "system") {
            listOf(LlmMessage("system", first.content + "\n\n" + inlinePrompt)) + messages.drop(1)
        } else {
            listOf(LlmMessage("system", inlinePrompt)) + messages
        }
    }

    internal fun buildRequestJson(messages: List<LlmMessage>, includeTools: Boolean = true): String {
        val messagesJson = messages.joinToString(",") { msg ->
            buildString {
                append("""{"role":${msg.role.toJsonString()},"content":${msg.content.toJsonString()}""")
                if (!msg.toolCalls.isNullOrEmpty()) {
                    append(""","tool_calls":[""")
                    append(msg.toolCalls.joinToString(",") { tc ->
                        """{"function":{"name":${tc.name.toJsonString()},"arguments":${InlineToolCallParser.argsToJson(tc.arguments)}}}"""
                    })
                    append("]")
                }
                append("}")
            }
        }
        val toolsJson = if (includeTools && tools.isNotEmpty()) {
            val defs = tools.joinToString(",") { t ->
                val parametersJson = t.argsType?.jsonSchema()
                    ?: """{"type":"object","properties":{},"additionalProperties":true}"""
                """{"type":"function","function":{"name":${t.name.toJsonString()},"description":${t.description.toJsonString()},"parameters":$parametersJson}}"""
            }
            ""","tools":[$defs]"""
        } else ""
        return """{"model":${model.toJsonString()},"stream":false,"temperature":$temperature,"messages":[$messagesJson]$toolsJson}"""
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
