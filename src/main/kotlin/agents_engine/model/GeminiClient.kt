package agents_engine.model

import agents_engine.generation.LenientJsonParser
import agents_engine.generation.jsonSchema
import agents_engine.internal.OPEN_EMPTY_OBJECT_SCHEMA_JSON
import agents_engine.internal.toJsonString
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
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * `agents_engine/model/GeminiClient.kt` — the Google Gemini (Generative Language API) adapter
 * (#1917), the fifth from-scratch [ModelClient] alongside [OllamaClient], [ClaudeClient],
 * [OpenAiClient], and [DeepSeekClient]. Gemini is **not** OpenAI-compatible, so this is a full
 * adapter rather than a thin subclass.
 *
 * Wire mapping (Generative Language API `:generateContent`):
 * - `LlmMessage("system", text)` → top-level `systemInstruction.parts[].text` (all system
 *   messages joined).
 * - `LlmMessage("user", text)` → `{role:"user", parts:[{text}]}`; vision parts become
 *   `{inlineData:{mimeType, data:<base64>}}` parts alongside the text.
 * - `LlmMessage("assistant", text, toolCalls)` → `{role:"model", parts:[{text}?,
 *   {functionCall:{name, args}}...]}`.
 * - `LlmMessage("tool", text)` → `{role:"user", parts:[{functionResponse:{name,
 *   response:{output:text}}}]}`. Gemini pairs by **function name** (no call id in the protocol),
 *   so tool results consume the pending function names from the most recent model turn in order.
 * - Tool defs → `tools:[{functionDeclarations:[{name, description, parametersJsonSchema}]}]`
 *   (Gemini's spelling; `parametersJsonSchema` accepts standard JSON Schema). No-arg tools omit
 *   the schema.
 * - [ToolChoice] → `toolConfig.functionCallingConfig.mode` (`AUTO` / `ANY` / `NONE`), with
 *   `allowedFunctionNames` for [ToolChoice.Specific].
 * - [JsonSchema] constrained decoding (tools empty) → `generationConfig.responseMimeType =
 *   "application/json"` + `responseJsonSchema`; the JSON text is returned for the normal
 *   `@Generable` parser (#1949).
 *
 * The top-level `error` envelope on the response surfaces as [LlmProviderException] — same
 * boundary contract as the other adapters (#702).
 */
open class GeminiClient(
    private val apiKey: String,
    private val model: String,
    private val temperature: Double = 0.7,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val tools: List<ToolDef> = emptyList(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiVersion: String = DEFAULT_API_VERSION,
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    private val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    /** #2408-style opt-in thinking. When enabled, requests thought summaries and surfaces them as reasoning. */
    private val reasoning: ReasoningConfig? = null,
    /** #2479 — vendor-neutral tool-choice mapped to Gemini's functionCallingConfig. */
    private val toolChoice: ToolChoice = ToolChoice.Auto,
    /** #2385 — optional shared `HttpClient`. */
    httpClient: HttpClient? = null,
) : ModelClient {

    internal val httpClient: HttpClient = httpClient ?: HttpClient.newBuilder()
        .connectTimeout(connectTimeout.toJavaDuration())
        .build()
    private val http: HttpClient get() = httpClient

    override fun supportsConstrainedDecoding(): Boolean = true

    override fun chat(messages: List<LlmMessage>): LlmResponse = chat(messages, jsonSchema = null)

    override fun chat(messages: List<LlmMessage>, jsonSchema: JsonSchema?): LlmResponse {
        val body = buildRequestJson(messages, jsonSchema = jsonSchema)
        val responseBody = sendChat(body, endpoint = "generateContent")
        return parseResponse(responseBody, jsonSchema = jsonSchema)
    }

    /** Test seam — subclasses override to stub HTTP without a server. */
    internal open fun sendChat(body: String, endpoint: String): String {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/$apiVersion/models/$model:$endpoint"))
            .timeout(requestTimeout.toJavaDuration())
            .header("content-type", "application/json")
            .header("x-goog-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        return HttpModelClientSupport.sendBounded(http, builder.build(), "Gemini", maxResponseBytes)
    }

    /**
     * Native SSE streaming via `:streamGenerateContent?alt=sse`. Each `data:` frame is a full
     * `GenerateContentResponse` delta: text parts are appended deltas, `functionCall` parts arrive
     * whole (Gemini does not stream argument fragments), and `usageMetadata` on the final chunk
     * carries token counts.
     */
    override suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> {
        val body = buildRequestJson(messages)
        return flow {
            sendChatStream(body).use { stream -> parseSseStream(stream, this) }
        }.flowOn(Dispatchers.IO)
    }

    /** Test seam — subclasses override to stub the streaming InputStream. */
    internal open fun sendChatStream(body: String): InputStream {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/$apiVersion/models/$model:streamGenerateContent?alt=sse"))
            .timeout(requestTimeout.toJavaDuration())
            .header("content-type", "application/json")
            .header("x-goog-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        // A non-2xx (e.g. 429 RESOURCE_EXHAUSTED, 400 bad key) returns a plain JSON error body, NOT
        // an SSE stream. Surface it as a boundary error instead of feeding it to the SSE parser
        // (which would silently yield no chunks). Mirrors sendBounded's error contract on the
        // non-streaming path.
        if (response.statusCode() !in 200..299) {
            val errText = response.body().use {
                String(it.readNBytes(MAX_ERROR_BODY_BYTES), Charsets.UTF_8)
            }
            throw LlmProviderException("Gemini streaming request failed (HTTP ${response.statusCode()}): $errText")
        }
        return response.body()
    }

    private suspend fun parseSseStream(stream: InputStream, collector: FlowCollector<LlmChunk>) {
        var lastUsage: TokenUsage? = null
        var emittedAnyToolCall = false
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
            for (raw in lines) {
                val data = sseDataObject(raw) ?: continue
                (data["error"] as? Map<*, *>)?.let { err ->
                    throw LlmProviderException("Gemini streaming error: ${(err["message"] as? String) ?: "unknown"}")
                }
                extractTokenUsage(data)?.let { lastUsage = it }
                if (emitParts(firstCandidateParts(data), collector)) emittedAnyToolCall = true
            }
        }
        // Single terminal End carrying the last cumulative usage (mirrors the other adapters).
        if (!emittedAnyToolCall || lastUsage != null) collector.emit(LlmChunk.End(lastUsage))
    }

    /** Parse one SSE line into its `data:` JSON object, or null for blanks / non-data / `[DONE]`. */
    private fun sseDataObject(rawLine: String): Map<*, *>? {
        val line = rawLine.trim()
        if (!line.startsWith("data:")) return null
        val dataJson = line.removePrefix("data:").trim()
        if (dataJson.isEmpty() || dataJson == "[DONE]") return null
        return LenientJsonParser.parse(dataJson) as? Map<*, *>
    }

    /** Emit chunks for one chunk's parts; returns true if any was a tool call. */
    private suspend fun emitParts(parts: List<Any?>, collector: FlowCollector<LlmChunk>): Boolean {
        var emittedToolCall = false
        for (part in parts) {
            val pm = part as? Map<*, *> ?: continue
            val fc = pm["functionCall"] as? Map<*, *>
            when {
                fc != null -> if (emitFunctionCall(fc, collector)) emittedToolCall = true
                pm["thought"] == true ->
                    (pm["text"] as? String)?.let { collector.emit(LlmChunk.ReasoningDelta(it)) }
                else -> (pm["text"] as? String)?.let { collector.emit(LlmChunk.TextDelta(it)) }
            }
        }
        return emittedToolCall
    }

    /** Gemini sends a `functionCall` whole (no streamed arg fragments) — expand to Started/Args/Finished. */
    private suspend fun emitFunctionCall(fc: Map<*, *>, collector: FlowCollector<LlmChunk>): Boolean {
        val name = fc["name"] as? String ?: return false
        val callId = "gemini_tool_${java.util.UUID.randomUUID()}"
        val args = asArgs(fc["args"])
        collector.emit(LlmChunk.ToolCallStarted(callId, name))
        collector.emit(LlmChunk.ToolCallArgumentsDelta(callId, InlineToolCallParser.argsToJson(args)))
        collector.emit(LlmChunk.ToolCallFinished(callId, args))
        return true
    }

    internal fun buildRequestJson(
        messages: List<LlmMessage>,
        jsonSchema: JsonSchema? = null,
    ): String {
        val systemMessages = messages.filter { it.role == "system" }
        val nonSystem = messages.filter { it.role != "system" }

        // Gemini pairs functionResponse to functionCall by NAME, in order. Track pending names
        // emitted by assistant turns; tool results consume them FIFO.
        val pendingFunctionNames = ArrayDeque<String>()
        val contents = nonSystem.mapNotNull { msg -> contentForMessage(msg, pendingFunctionNames) }

        val systemField = if (systemMessages.isEmpty()) "" else {
            val joined = systemMessages.joinToString("\n\n") { it.content }
            ""","systemInstruction":{"parts":[{"text":${joined.toJsonString()}}]}"""
        }

        val structuredSchema = jsonSchema?.takeIf { tools.isEmpty() }
        val toolsField = if (tools.isEmpty() || (toolChoice == ToolChoice.None && structuredSchema == null)) "" else {
            val decls = tools.joinToString(",") { t ->
                val schema = t.argsType?.jsonSchema() ?: t.parametersSchemaJson
                val paramsField = if (schema == null || schema == OPEN_EMPTY_OBJECT_SCHEMA_JSON) {
                    ""
                } else {
                    ""","parametersJsonSchema":$schema"""
                }
                """{"name":${t.name.toJsonString()},"description":${t.description.toJsonString()}$paramsField}"""
            }
            ""","tools":[{"functionDeclarations":[$decls]}]"""
        }
        val toolConfigField = toolConfigField(structuredSchema)

        val genConfig = buildString {
            append(""""temperature":$temperature,"maxOutputTokens":$maxTokens""")
            if (reasoning?.enabled == true) {
                val budget = reasoning.budgetTokens
                append(""","thinkingConfig":{"includeThoughts":true""")
                if (budget != null) append(""","thinkingBudget":$budget""")
                append("}")
            }
            if (structuredSchema != null) {
                append(""","responseMimeType":"application/json","responseJsonSchema":${structuredSchema.schema}""")
            }
        }
        // Streaming is encoded in the URL (:streamGenerateContent?alt=sse), not the body, so the
        // request JSON is identical for chat() and chatStream().
        return """{"contents":[${contents.joinToString(",")}]$systemField$toolsField$toolConfigField,""" +
            """"generationConfig":{$genConfig}}"""
    }

    private fun contentForMessage(msg: LlmMessage, pendingFunctionNames: ArrayDeque<String>): String? =
        when (msg.role) {
            "user" -> {
                val parts = buildList {
                    if (msg.content.isNotEmpty() || msg.images.isNullOrEmpty()) {
                        add("""{"text":${msg.content.toJsonString()}}""")
                    }
                    msg.images?.forEach { part ->
                        add(
                            """{"inlineData":{"mimeType":${part.wireMime.value.toJsonString()},""" +
                                """"data":${part.base64.toJsonString()}}}""",
                        )
                    }
                }
                """{"role":"user","parts":[${parts.joinToString(",")}]}"""
            }

            "assistant" -> {
                val parts = buildList {
                    if (msg.content.isNotEmpty()) add("""{"text":${msg.content.toJsonString()}}""")
                    msg.toolCalls?.forEach { call ->
                        pendingFunctionNames.addLast(call.name)
                        val argsJson = InlineToolCallParser.argsToJson(call.arguments)
                        add("""{"functionCall":{"name":${call.name.toJsonString()},"args":$argsJson}}""")
                    }
                    if (isEmpty()) add("""{"text":""}""")
                }
                """{"role":"model","parts":[${parts.joinToString(",")}]}"""
            }

            "tool" -> {
                val name = pendingFunctionNames.removeFirstOrNull()
                    ?: error("tool result with no preceding model functionCall to pair with")
                """{"role":"user","parts":[{"functionResponse":{"name":${name.toJsonString()},""" +
                    """"response":{"output":${msg.content.toJsonString()}}}}]}"""
            }

            else -> error("Unknown LlmMessage role for Gemini: '${msg.role}'")
        }

    private fun toolConfigField(structuredSchema: JsonSchema?): String {
        if (tools.isEmpty() || structuredSchema != null) return ""
        return when (val tc = toolChoice) {
            ToolChoice.Auto -> ""
            ToolChoice.None -> ""","toolConfig":{"functionCallingConfig":{"mode":"NONE"}}"""
            ToolChoice.Required -> ""","toolConfig":{"functionCallingConfig":{"mode":"ANY"}}"""
            is ToolChoice.Specific ->
                ""","toolConfig":{"functionCallingConfig":{"mode":"ANY",""" +
                    """"allowedFunctionNames":[${tc.name.toJsonString()}]}}"""
        }
    }

    internal fun parseResponse(body: String, jsonSchema: JsonSchema? = null): LlmResponse {
        val root = LenientJsonParser.parse(body) as? Map<*, *> ?: return LlmResponse.Text(body)
        (root["error"] as? Map<*, *>)?.let { err ->
            val status = err["status"] as? String
            val message = err["message"] as? String
            throw LlmProviderException("Gemini returned an error: ${status ?: "unknown"}: ${message ?: "no message"}")
        }

        val tokenUsage = extractTokenUsage(root)
        val parts = firstCandidateParts(root)
        if (parts.isEmpty()) return LlmResponse.Text(body, tokenUsage)

        val partMaps = parts.mapNotNull { it as? Map<*, *> }
        val reasoningText = partMaps
            .filter { it["thought"] == true }
            .mapNotNull { it["text"] as? String }
            .joinToString("")
            .ifEmpty { null }

        val functionCalls = partMaps.mapNotNull { it["functionCall"] as? Map<*, *> }
        if (jsonSchema != null && functionCalls.isEmpty()) {
            val text = partMaps.filter { it["thought"] != true }
                .mapNotNull { it["text"] as? String }.joinToString("")
            return LlmResponse.Text(text, tokenUsage, reasoningText)
        }
        if (functionCalls.isNotEmpty()) {
            val calls = functionCalls.mapNotNull { fc ->
                val name = fc["name"] as? String ?: return@mapNotNull null
                val parsed = parseToolArguments(asArgs(fc["args"]))
                ToolCall(
                    name = name,
                    arguments = parsed.arguments,
                    rawArguments = parsed.rawArguments,
                    invalidArgumentsError = parsed.parseError,
                )
            }
            if (calls.isNotEmpty()) return LlmResponse.ToolCalls(calls, tokenUsage, reasoningText)
        }

        val text = partMaps.filter { it["thought"] != true }
            .mapNotNull { it["text"] as? String }
            .joinToString("")
        return LlmResponse.Text(text, tokenUsage, reasoningText)
    }

    private fun extractTokenUsage(root: Map<*, *>): TokenUsage? {
        val usage = root["usageMetadata"] as? Map<*, *> ?: return null
        val prompt = (usage["promptTokenCount"] as? Number)?.toInt()
        val completion = (usage["candidatesTokenCount"] as? Number)?.toInt()
        val cached = (usage["cachedContentTokenCount"] as? Number)?.toInt()
        val thoughts = (usage["thoughtsTokenCount"] as? Number)?.toInt()
        return if (prompt != null && completion != null) {
            TokenUsage(
                promptTokens = prompt,
                completionTokens = completion,
                cachedInputTokens = cached,
                reasoningTokens = thoughts,
                provider = "gemini",
                model = model,
            )
        } else null
    }

    private fun firstCandidateParts(root: Map<*, *>): List<Any?> {
        val candidates = root["candidates"] as? List<*> ?: return emptyList()
        val first = candidates.firstOrNull() as? Map<*, *> ?: return emptyList()
        val content = first["content"] as? Map<*, *> ?: return emptyList()
        return content["parts"] as? List<*> ?: emptyList()
    }

    private fun asArgs(raw: Any?): Map<String, Any?> =
        (raw as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()

    companion object {
        const val DEFAULT_BASE_URL: String = "https://generativelanguage.googleapis.com"
        const val DEFAULT_API_VERSION: String = "v1beta"
        val DEFAULT_REQUEST_TIMEOUT: Duration = 300.seconds
        val DEFAULT_CONNECT_TIMEOUT: Duration = 10.seconds
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 16L * 1024 * 1024

        // Cap on the error body read from a non-2xx streaming response (e.g. a 429 JSON envelope).
        private const val MAX_ERROR_BODY_BYTES: Int = 8 * 1024

        // Gemini does not require maxOutputTokens, but we always send one for parity with the
        // other adapters' budget surface; 4096 suits chat-shaped turns.
        const val DEFAULT_MAX_TOKENS: Int = 4096
    }
}
