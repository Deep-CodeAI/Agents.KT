package agents_engine.model

import agents_engine.generation.LenientJsonParser
import agents_engine.generation.jsonSchema
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * `agents_engine/model/OllamaClient.kt` — the local Ollama HTTP adapter,
 * the framework's default `ModelClient`. Targets `POST /api/chat` on
 * `localhost:11434` by default; tools surface as native Ollama tool calls.
 * `JsonSchema` constrained decoding uses Ollama's top-level `format`
 * field (#1949). Streaming via NDJSON. See
 * `src/main/resources/internals-agent/model/OllamaClient.md` for the
 * adjunct surfaced to IDE-side LLM tools (#1837 / #1852).
 */

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
    /**
     * #2385 — optional `HttpClient` injection. When non-null the client
     * uses it verbatim; the caller-supplied instance can carry its own
     * executor (rate-limiting / bulkhead), connection pool, proxy, or
     * telemetry interceptors. When null, builds a per-client `HttpClient`
     * with [connectTimeout] (legacy behavior, byte-for-byte unchanged).
     */
    httpClient: HttpClient? = null,
) : ModelClient {
    private val baseUrl = "http://$host:$port"

    /**
     * #2385 — test/inspection seam. `internal` matches the existing
     * `sendChat` / `buildRequestJson` test seams. Production callers
     * should configure via the `httpClient` constructor parameter.
     */
    internal val httpClient: HttpClient = httpClient ?: HttpClient.newBuilder()
        .connectTimeout(connectTimeout.toJavaDuration())
        .build()
    private val http: HttpClient get() = this.httpClient

    /**
     * #706: Once a model has been observed to reject native tools, skip the native
     * attempt on subsequent calls and go straight to the inline-prompt path. This
     * matters for the agentic loop, which calls `chat()` multiple times per turn —
     * without the latch we'd burn an extra HTTP roundtrip per turn re-discovering
     * the same incapability.
     */
    @Volatile private var nativeToolsKnownUnsupported: Boolean = false

    override fun supportsConstrainedDecoding(): Boolean = true

    override fun chat(messages: List<LlmMessage>): LlmResponse =
        chat(messages, jsonSchema = null)

    override fun chat(messages: List<LlmMessage>, jsonSchema: JsonSchema?): LlmResponse {
        if (tools.isNotEmpty() && nativeToolsKnownUnsupported) {
            return withTransientRetry {
                parseResponse(sendChat(buildRequestJson(
                    messages = withInlineToolPrompt(messages),
                    includeTools = false,
                    jsonSchema = jsonSchema,
                )))
            }
        }
        val body = buildRequestJson(messages, includeTools = true, jsonSchema = jsonSchema)
        return withTransientRetry {
            val responseBody = sendChat(body)
            try {
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
                    val inlineBody = buildRequestJson(inlineMessages, includeTools = false, jsonSchema = jsonSchema)
                    parseResponse(sendChat(inlineBody))
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * #2381 — retry transient Ollama failures (transport-level errors that
     * arrive wrapped in Ollama's `{"error":"..."}` envelope: edge-layer
     * `unexpected EOF`, `Internal Server Error`, `Bad Gateway`, etc.).
     *
     * Non-transient errors — model-not-found, capability mismatch, auth,
     * malformed-request — fail fast: the caller needs that signal now,
     * and retrying makes the wrong call slower without fixing anything.
     *
     * Backoff is short (250ms, 500ms) — the goal is to ride out a single
     * dropped connection or 5xx blip, not to absorb a sustained outage.
     * Total worst-case latency added: ~750ms.
     */
    private fun <T> withTransientRetry(op: () -> T): T {
        var lastException: LlmProviderException? = null
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try {
                return op()
            } catch (e: LlmProviderException) {
                if (!isTransientProviderError(e.message)) throw e
                lastException = e
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    val backoffMs = RETRY_INITIAL_BACKOFF_MS shl attempt
                    Thread.sleep(backoffMs)
                }
            }
        }
        throw lastException ?: error("withTransientRetry exited without exception or result")
    }

    private fun isTransientProviderError(message: String?): Boolean = message?.let { msg ->
        TRANSIENT_ERROR_PATTERNS.any { msg.contains(it, ignoreCase = true) }
    } ?: false

    /**
     * #1741 — native streaming via Ollama's NDJSON protocol (`stream: true`).
     * One JSON object per line. Intermediate lines carry partial
     * `message.content`; the final `done:true` line carries tool calls
     * (if any) plus `prompt_eval_count` + `eval_count`.
     *
     * Tool calls are NOT progressively streamed by Ollama — they land all
     * at once in the final chunk. We emit the canonical
     * `ToolCallStarted` / `ToolCallArgumentsDelta` / `ToolCallFinished`
     * triple for each so consumers see the same shape they would from
     * a progressively-streaming provider; the wire-level granularity
     * is just coarser.
     *
     * HTTP cancellation: the underlying `HttpClient.send(...)` blocks
     * inside `BufferedReader.readLine()`. Coroutine cancellation can't
     * interrupt the blocking read mid-line. Step 4 will migrate to
     * `sendAsync` for true cancellation propagation.
     */
    override suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> =
        chatStream(messages, jsonSchema = null)

    override suspend fun chatStream(messages: List<LlmMessage>, jsonSchema: JsonSchema?): Flow<LlmChunk> {
        val nativeToolsActive = tools.isNotEmpty() && !nativeToolsKnownUnsupported
        val effectiveMessages = if (tools.isNotEmpty() && nativeToolsKnownUnsupported) {
            withInlineToolPrompt(messages)
        } else {
            messages
        }
        val body = buildRequestJson(
            messages = effectiveMessages,
            includeTools = nativeToolsActive,
            stream = true,
            jsonSchema = jsonSchema,
        )
        return flow {
            sendChatStream(body).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        val parsed = LenientJsonParser.parse(line) as? Map<*, *> ?: continue
                        val done = parsed["done"] as? Boolean ?: false
                        val message = parsed["message"] as? Map<*, *>
                        val content = (message?.get("content") as? String) ?: ""
                        if (content.isNotEmpty()) emit(LlmChunk.TextDelta(content))
                        if (done) {
                            val rawToolCalls = message?.get("tool_calls") as? List<*>
                            rawToolCalls?.forEach { tc ->
                                val fn = (tc as? Map<*, *>)?.get("function") as? Map<*, *> ?: return@forEach
                                val name = fn["name"] as? String ?: return@forEach
                                val parsedArgs = parseToolArguments(fn["arguments"])
                                val callId = java.util.UUID.randomUUID().toString()
                                emit(LlmChunk.ToolCallStarted(callId, name))
                                emit(LlmChunk.ToolCallArgumentsDelta(callId, parsedArgs.rawArguments ?: ""))
                                emit(LlmChunk.ToolCallFinished(callId, parsedArgs.arguments))
                            }
                            emit(LlmChunk.End(extractOllamaTokenUsage(parsed)))
                            break
                        }
                    }
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    /** Test seam (#1741): subclasses override to stub the streaming InputStream. */
    internal open fun sendChatStream(body: String): InputStream {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/chat"))
            .header("Content-Type", "application/json")
            .timeout(requestTimeout.toJavaDuration())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        return response.body()
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

        // #2381 — transient retry tuning. Three attempts total (initial + 2
        // retries) with 250ms / 500ms backoffs; worst-case adds ~750ms.
        private const val MAX_RETRY_ATTEMPTS: Int = 3
        private const val RETRY_INITIAL_BACKOFF_MS: Long = 250

        // Patterns that identify transport-level transient failures wrapped
        // in Ollama's `{"error":"..."}` envelope. Case-insensitive substring
        // match. Add patterns here as new transient classes appear in the
        // wild — keep model-not-found / capability / auth messages OUT so
        // those still fail fast.
        private val TRANSIENT_ERROR_PATTERNS: List<String> = listOf(
            "unexpected EOF",
            "Internal Server Error",
            "Service Unavailable",
            "Bad Gateway",
            "Gateway Timeout",
            "connection reset",
        )
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

    internal fun buildRequestJson(
        messages: List<LlmMessage>,
        includeTools: Boolean = true,
        stream: Boolean = false,
        jsonSchema: JsonSchema? = null,
    ): String {
        val messagesJson = messages.joinToString(",") { msg ->
            buildString {
                // #1694 — On assistant turns that carry tool_calls, content must
                // be JSON null per the OpenAI / Ollama chat-completions spec.
                // Ollama Cloud's strict gpt-oss:* validators reject the
                // empty-string form with 500 Internal Server Error; local
                // Ollama tolerates either. Null-coerce only when:
                //   role == assistant AND tool_calls non-empty AND content blank.
                // A genuine empty-string assistant turn with no tool_calls is
                // preserved as "content":"" (different semantics).
                val toolCalls = msg.toolCalls
                val toolCallsPresent = !toolCalls.isNullOrEmpty()
                val contentJson = if (msg.role == "assistant" && toolCallsPresent && msg.content.isEmpty()) {
                    "null"
                } else {
                    msg.content.toJsonString()
                }
                append("""{"role":${msg.role.toJsonString()},"content":$contentJson""")
                if (toolCallsPresent) {
                    append(""","tool_calls":[""")
                    append(toolCalls.orEmpty().joinToString(",") { tc ->
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
                    ?: t.parametersSchemaJson
                    ?: """{"type":"object","properties":{},"additionalProperties":true}"""
                """{"type":"function","function":{"name":${t.name.toJsonString()},"description":${t.description.toJsonString()},"parameters":$parametersJson}}"""
            }
            ""","tools":[$defs]"""
        } else ""
        val formatJson = jsonSchema?.let { ""","format":${it.schema}""" } ?: ""
        return """{"model":${model.toJsonString()},"stream":$stream,"temperature":$temperature,"messages":[$messagesJson]$toolsJson$formatJson}"""
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
        // #963: Ollama reports prompt + completion token counts at the response root.
        // Both must be present for the count to be trustworthy — partial reports get
        // dropped (null) so the loop's accumulator can distinguish "0 tokens used"
        // from "provider didn't say."
        val tokenUsage = extractOllamaTokenUsage(root)

        val message = root["message"] as? Map<*, *>
            ?: return LlmResponse.Text(body, tokenUsage)
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
            if (calls.isNotEmpty()) return LlmResponse.ToolCalls(calls, tokenUsage)
        }

        // Inline JSON tool call in content (models without native tool support)
        val toolCall = InlineToolCallParser.parse(content)
        if (toolCall != null) return LlmResponse.ToolCalls(listOf(toolCall), tokenUsage)

        return LlmResponse.Text(content, tokenUsage)
    }

    private fun extractOllamaTokenUsage(root: Map<*, *>): TokenUsage? {
        val prompt = (root["prompt_eval_count"] as? Number)?.toInt()
        val completion = (root["eval_count"] as? Number)?.toInt()
        return if (prompt != null && completion != null) {
            TokenUsage(
                promptTokens = prompt,
                completionTokens = completion,
                cachedInputTokens = null,
                provider = "ollama",
                model = model,
            )
        } else null
    }
}

