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
 * `agents_engine/model/ClaudeClient.kt` — the Anthropic Messages API
 * adapter (#1644), one of the shipped [ModelClient] implementations
 * (alongside [OllamaClient], [OpenAiClient], and [DeepSeekClient]). See
 * `src/main/resources/internals-agent/model/ClaudeClient.md` for the
 * adjunct surfaced to IDE-side LLM tools via `agents-kt-internals`
 * (#1837 / #1846).
 */

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
 * - `JsonSchema` constrained decoding → a forced `structured_output`
 *   tool when no real tools are present; the response is converted back
 *   into final JSON text for the normal `@Generable` parser (#1949).
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

    override fun supportsConstrainedDecoding(): Boolean = true

    override fun chat(messages: List<LlmMessage>): LlmResponse =
        chat(messages, jsonSchema = null)

    override fun chat(messages: List<LlmMessage>, jsonSchema: JsonSchema?): LlmResponse {
        val body = buildRequestJson(messages, jsonSchema = jsonSchema)
        val headers = mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to anthropicVersion,
            "content-type" to "application/json",
        )
        val responseBody = sendChat(body, headers)
        return parseResponse(responseBody, jsonSchema = jsonSchema)
    }

    /**
     * #1742 — native SSE streaming. Anthropic's protocol uses indexed
     * content blocks; chunks for different blocks (text vs tool_use)
     * can interleave by index. We track block metadata by index and
     * route each delta to the right block's id/builder.
     *
     * Block lifecycle:
     * - `content_block_start` records the block's type. For `tool_use`,
     *   captures the canonical Anthropic id (`toolu_*`) which becomes
     *   the [LlmChunk.ToolCallStarted.callId] verbatim.
     * - `content_block_delta` with `text_delta` → [LlmChunk.TextDelta].
     * - `content_block_delta` with `input_json_delta` → appends to the
     *   block's argsBuilder + emits [LlmChunk.ToolCallArgumentsDelta].
     * - `content_block_stop` for a tool_use block parses the assembled
     *   JSON and emits [LlmChunk.ToolCallFinished].
     *
     * Token usage spans events: `message_start.usage.input_tokens`
     * pairs with `message_delta.usage.output_tokens` (running total),
     * bundled into the terminal [LlmChunk.End] at `message_stop`.
     */
    override suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> {
        val body = buildRequestJson(messages, stream = true)
        val headers = mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to anthropicVersion,
            "content-type" to "application/json",
        )
        return flow {
            sendChatStream(body, headers).use { stream ->
                parseSseStream(stream, this)
            }
        }.flowOn(Dispatchers.IO)
    }

    /** Test seam — subclasses override to stub the streaming InputStream. */
    internal open fun sendChatStream(body: String, headers: Map<String, String>): InputStream {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/messages"))
            .timeout(requestTimeout.toJavaDuration())
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        return response.body()
    }

    /**
     * Per-block state for SSE aggregation. `id` is set for `tool_use`
     * blocks (the Anthropic-issued `toolu_*` id used as `callId`);
     * null for text blocks. `argsBuilder` accumulates `input_json_delta`
     * fragments and is parsed at `content_block_stop`.
     */
    private data class BlockState(
        val type: String,
        val id: String?,
        val name: String?,
        val argsBuilder: StringBuilder,
    )

    private suspend fun parseSseStream(stream: InputStream, collector: kotlinx.coroutines.flow.FlowCollector<LlmChunk>) {
        val blocks = mutableMapOf<Int, BlockState>()
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var cachedInputTokens: Int? = null

        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
            // SSE: lines are `event: <name>`, `data: <json>`, or blank.
            // Blank line separates events. We accumulate the current
            // event's type + data, then dispatch on the blank line OR
            // at end-of-stream (some upstreams don't emit a trailing
            // blank after the final message_stop).
            var currentEvent: String? = null
            var currentData: String? = null
            suspend fun dispatch() {
                val evt = currentEvent
                val data = currentData
                currentEvent = null
                currentData = null
                if (evt != null && data != null) {
                    dispatchSseEvent(evt, data, blocks, collector,
                        onInputTokens = { inputTokens = it },
                        onCachedInputTokens = { cachedInputTokens = it },
                        onOutputTokens = { outputTokens = it },
                        onMessageStop = {
                            val prompt = inputTokens
                            val completion = outputTokens
                            collector.emit(
                                LlmChunk.End(
                                    tokenUsage = if (prompt != null && completion != null) {
                                        TokenUsage(
                                            promptTokens = prompt,
                                            completionTokens = completion,
                                            cachedInputTokens = cachedInputTokens,
                                            provider = "claude",
                                            model = model,
                                        )
                                    } else null,
                                )
                            )
                        },
                    )
                }
            }
            for (line in lines) {
                when {
                    line.isBlank() -> dispatch()
                    line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> currentData = line.removePrefix("data:").trim()
                    // Other prefixes (id:, retry:, comments) — ignore.
                }
            }
            // Drain any final event missing its trailing blank line.
            dispatch()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun dispatchSseEvent(
        event: String,
        dataJson: String,
        blocks: MutableMap<Int, BlockState>,
        collector: kotlinx.coroutines.flow.FlowCollector<LlmChunk>,
        onInputTokens: (Int) -> Unit,
        onCachedInputTokens: (Int) -> Unit,
        onOutputTokens: (Int) -> Unit,
        onMessageStop: suspend () -> Unit,
    ) {
        val data = LenientJsonParser.parse(dataJson) as? Map<String, Any?> ?: return
        when (event) {
            "message_start" -> {
                val message = data["message"] as? Map<String, Any?> ?: return
                val usage = message["usage"] as? Map<String, Any?> ?: return
                (usage["input_tokens"] as? Number)?.toInt()?.let(onInputTokens)
                (usage["cache_read_input_tokens"] as? Number)?.toInt()?.let(onCachedInputTokens)
            }
            "content_block_start" -> {
                val index = (data["index"] as? Number)?.toInt() ?: return
                val block = data["content_block"] as? Map<String, Any?> ?: return
                val type = block["type"] as? String ?: return
                val id = block["id"] as? String
                val name = block["name"] as? String
                blocks[index] = BlockState(type, id, name, StringBuilder())
                if (type == "tool_use" && id != null && name != null) {
                    collector.emit(LlmChunk.ToolCallStarted(callId = id, toolName = name))
                }
            }
            "content_block_delta" -> {
                val index = (data["index"] as? Number)?.toInt() ?: return
                val delta = data["delta"] as? Map<String, Any?> ?: return
                val block = blocks[index] ?: return
                when (delta["type"] as? String) {
                    "text_delta" -> {
                        val text = delta["text"] as? String ?: return
                        collector.emit(LlmChunk.TextDelta(text))
                    }
                    "input_json_delta" -> {
                        val partial = delta["partial_json"] as? String ?: return
                        block.argsBuilder.append(partial)
                        val id = block.id ?: return
                        collector.emit(LlmChunk.ToolCallArgumentsDelta(callId = id, deltaJson = partial))
                    }
                }
            }
            "content_block_stop" -> {
                val index = (data["index"] as? Number)?.toInt() ?: return
                val block = blocks.remove(index) ?: return
                if (block.type == "tool_use" && block.id != null) {
                    val args = block.argsBuilder.toString()
                    val parsed = if (args.isBlank()) emptyMap() else parseToolArguments(args).arguments
                    collector.emit(LlmChunk.ToolCallFinished(callId = block.id, arguments = parsed))
                }
            }
            "message_delta" -> {
                val usage = data["usage"] as? Map<String, Any?> ?: return
                (usage["output_tokens"] as? Number)?.toInt()?.let(onOutputTokens)
            }
            "message_stop" -> onMessageStop()
            "error" -> {
                val errMsg = (data["error"] as? Map<*, *>)?.get("message") as? String ?: "unknown"
                throw LlmProviderException("Claude streaming error: $errMsg")
            }
            // "ping" and other events — ignore.
        }
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

    internal fun buildRequestJson(
        messages: List<LlmMessage>,
        stream: Boolean = false,
        jsonSchema: JsonSchema? = null,
    ): String {
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
        val structuredSchema = jsonSchema?.takeIf { tools.isEmpty() }
        val toolDefs = buildList {
            tools.forEach { t ->
                val schema = t.argsType?.jsonSchema()
                    ?: t.parametersSchemaJson
                    ?: """{"type":"object","properties":{},"additionalProperties":true}"""
                add("""{"name":${t.name.toJsonString()},"description":${t.description.toJsonString()},"input_schema":$schema}""")
            }
            structuredSchema?.let { schema ->
                add(
                    """{"name":${STRUCTURED_OUTPUT_TOOL_NAME.toJsonString()},"description":"Return the final response using the requested JSON schema.","input_schema":${schema.schema}}"""
                )
            }
        }
        val toolsField = if (toolDefs.isNotEmpty()) ""","tools":[${toolDefs.joinToString(",")}]""" else ""
        val toolChoiceField = if (structuredSchema != null) {
            ""","tool_choice":{"type":"tool","name":${STRUCTURED_OUTPUT_TOOL_NAME.toJsonString()}}"""
        } else ""

        val streamField = if (stream) ""","stream":true""" else ""
        return """{"model":${model.toJsonString()},"max_tokens":$maxTokens,"temperature":$temperature$streamField$systemField,"messages":[${messageObjects.joinToString(",")}]$toolsField$toolChoiceField}"""
    }

    internal fun parseResponse(body: String, jsonSchema: JsonSchema? = null): LlmResponse {
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
        if (jsonSchema != null) {
            val structured = toolUses.firstOrNull { it["name"] == STRUCTURED_OUTPUT_TOOL_NAME }
            if (structured != null) {
                val parsed = parseToolArguments(structured["input"])
                return LlmResponse.Text(InlineToolCallParser.argsToJson(parsed.arguments), tokenUsage)
            }
        }
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
        val cached = (usage["cache_read_input_tokens"] as? Number)?.toInt()
        return if (input != null && output != null) {
            TokenUsage(
                promptTokens = input,
                completionTokens = output,
                cachedInputTokens = cached,
                provider = "claude",
                model = model,
            )
        } else null
    }

    companion object {
        private const val STRUCTURED_OUTPUT_TOOL_NAME = "structured_output"

        val DEFAULT_REQUEST_TIMEOUT: Duration = 60.seconds
        val DEFAULT_CONNECT_TIMEOUT: Duration = 10.seconds
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 16L * 1024 * 1024

        // Anthropic requires max_tokens on every Messages request. 4096 is a
        // reasonable default for chat-shaped turns; callers tuning long-form
        // generation should set their own.
        const val DEFAULT_MAX_TOKENS: Int = 4096
    }
}

