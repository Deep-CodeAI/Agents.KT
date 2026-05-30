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
 * `agents_engine/model/OpenAiClient.kt` — OpenAI Chat Completions adapter
 * (#1656), one of the shipped [ModelClient] implementations. See
 * `src/main/resources/internals-agent/model/OpenAiClient.md` for the
 * adjunct surfaced to IDE-side LLM tools (#1837 / #1855).
 */

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
 * - `JsonSchema` constrained decoding → top-level `response_format` with
 *   `type:"json_schema"` and `strict:true` (#1949).
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
    private val providerName: String = "openai",
    private val providerLabel: String = "OpenAI",
    /**
     * #2411 — opt-in reasoning. OpenAI Chat Completions takes `reasoning_effort`
     * and reports `reasoning_tokens` (no reasoning TEXT on the wire). Subclasses
     * (DeepSeek) read it to gate their own reasoning behavior. Off when null.
     */
    protected val reasoning: ReasoningConfig? = null,
    /**
     * #2659 — optional `prompt_cache_key` for OpenAI's automatic prefix-caching
     * routing. A stable string groups same-shape requests onto the same cache
     * shard, improving hit rate. Set by the agentic loop to a derivative of
     * the agent identity + manifest hash when [CacheConfig.enabled] is on;
     * null = field omitted from the request (consumer-supplied subclasses
     * may pass it directly).
     */
    private val promptCacheKey: String? = null,
    /**
     * #2479 part 2 — vendor-neutral tool-choice control. The agentic loop
     * passes this through from `agent.toolChoice`. [ToolChoice.Auto] omits
     * the field entirely (preserves pre-#2479-pt2 wire shape). [ToolChoice
     * .None] also drops the `tools` array from the request so the model
     * literally cannot call anything.
     */
    private val toolChoice: ToolChoice = ToolChoice.Auto,
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
            "Authorization" to "Bearer $apiKey",
            "content-type" to "application/json",
        )
        val responseBody = sendChat(body, headers)
        return parseResponse(responseBody)
    }

    /**
     * #1743 — native SSE streaming. OpenAI's protocol is `data:`-only
     * (no `event:` names), terminated by the literal `data: [DONE]`.
     *
     * Tool-call correlation: the `id` (`call_*`) arrives in the FIRST
     * delta for a given `tool_calls[].index`; subsequent deltas omit
     * it. The aggregator caches `index -> id` after first sighting.
     *
     * Arguments arrive as concatenated string fragments. We emit
     * `LlmChunk.ToolCallArgumentsDelta` per non-empty fragment and
     * accumulate into a buffer; on `finish_reason: "tool_calls"` we
     * parse the buffer and emit `LlmChunk.ToolCallFinished`.
     *
     * Token usage requires `stream_options.include_usage: true` (set in
     * `buildRequestJson(stream=true)`). OpenAI then sends a final
     * usage-only delta with `choices: []` and `usage: {...}`. We capture
     * it and emit `LlmChunk.End(usage)` when `[DONE]` arrives.
     */
    override suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> =
        chatStream(messages, jsonSchema = null)

    override suspend fun chatStream(messages: List<LlmMessage>, jsonSchema: JsonSchema?): Flow<LlmChunk> {
        val body = buildRequestJson(messages, stream = true, jsonSchema = jsonSchema)
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
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
            .uri(URI.create("$baseUrl/v1/chat/completions"))
            .timeout(requestTimeout.toJavaDuration())
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        return response.body()
    }

    /** Per-tool-call streaming state. */
    private data class ToolCallState(
        var id: String? = null,
        var name: String? = null,
        val argsBuilder: StringBuilder = StringBuilder(),
    )

    private suspend fun parseSseStream(stream: InputStream, collector: kotlinx.coroutines.flow.FlowCollector<LlmChunk>) {
        // Keyed by `tool_calls[].index` within the choice.
        val toolStates = mutableMapOf<Int, ToolCallState>()
        var usage: TokenUsage? = null

        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || !line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") {
                    collector.emit(LlmChunk.End(usage))
                    return@useLines
                }
                @Suppress("UNCHECKED_CAST")
                val data = LenientJsonParser.parse(payload) as? Map<String, Any?> ?: continue
                // Final usage-only delta: choices is empty, usage non-null.
                (data["usage"] as? Map<*, *>)?.let { u ->
                    usage = tokenUsageFromUsageMap(u)
                }
                val choices = data["choices"] as? List<*> ?: continue
                val choice = choices.firstOrNull() as? Map<*, *> ?: continue
                val delta = choice["delta"] as? Map<*, *>
                val finishReason = choice["finish_reason"] as? String

                // #2409 — reasoning delta (DeepSeek reasoner / OpenAI-compatible),
                // streamed ahead of the answer. OpenAI proper omits it.
                (delta?.get("reasoning_content") as? String)?.takeIf { it.isNotEmpty() }?.let {
                    collector.emit(LlmChunk.ReasoningDelta(it))
                }

                // Text content delta.
                (delta?.get("content") as? String)?.takeIf { it.isNotEmpty() }?.let {
                    collector.emit(LlmChunk.TextDelta(it))
                }

                // Tool-call deltas.
                val rawToolCalls = delta?.get("tool_calls") as? List<*>
                rawToolCalls?.forEach { tc ->
                    val tcMap = tc as? Map<*, *> ?: return@forEach
                    val tcIndex = (tcMap["index"] as? Number)?.toInt() ?: return@forEach
                    val state = toolStates.getOrPut(tcIndex) { ToolCallState() }
                    val newId = tcMap["id"] as? String
                    val fn = tcMap["function"] as? Map<*, *>
                    val newName = fn?.get("name") as? String
                    val argsFragment = fn?.get("arguments") as? String

                    // First sighting: id + name typically present together.
                    if (state.id == null && newId != null) {
                        state.id = newId
                        if (newName != null) state.name = newName
                        collector.emit(LlmChunk.ToolCallStarted(callId = newId, toolName = newName ?: ""))
                    } else if (newName != null && state.name == null) {
                        state.name = newName
                    }

                    if (!argsFragment.isNullOrEmpty()) {
                        state.argsBuilder.append(argsFragment)
                        val callId = state.id
                        if (callId != null) {
                            collector.emit(LlmChunk.ToolCallArgumentsDelta(callId = callId, deltaJson = argsFragment))
                        }
                    }
                }

                // finish_reason == "tool_calls" marks completion of the
                // assistant turn's tool-call sequence; emit Finished for
                // each accumulated call.
                if (finishReason == "tool_calls") {
                    toolStates.values.forEach { state ->
                        val callId = state.id ?: return@forEach
                        val argsString = state.argsBuilder.toString()
                        val parsed = if (argsString.isBlank()) emptyMap()
                                     else parseToolArguments(argsString).arguments
                        collector.emit(LlmChunk.ToolCallFinished(callId = callId, arguments = parsed))
                    }
                    toolStates.clear()
                }
            }
            // EOF without [DONE]: emit End with whatever usage we captured.
            collector.emit(LlmChunk.End(usage))
        }
    }

    /** Test seam — subclasses override to stub HTTP without a server. */
    internal open fun sendChat(body: String, headers: Map<String, String>): String {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/chat/completions"))
            .timeout(requestTimeout.toJavaDuration())
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> builder.header(k, v) }
        // #2792 — bounded read + OOM guard moved to HttpModelClientSupport.
        return HttpModelClientSupport.sendBounded(http, builder.build(), providerLabel, maxResponseBytes)
    }

    internal fun buildRequestJson(
        messages: List<LlmMessage>,
        stream: Boolean = false,
        jsonSchema: JsonSchema? = null,
    ): String {
        val pendingToolCallIds: ArrayDeque<String> = ArrayDeque()
        var toolCallCounter = 0

        val messageObjects = messages.map { msg ->
            when (msg.role) {
                "system" ->
                    """{"role":"system","content":${msg.content.toJsonString()}}"""
                "user" -> {
                    val images = msg.images
                    if (!images.isNullOrEmpty()) {
                        // #2470 — vision input. OpenAI Chat Completions
                        // accepts a content array of typed blocks; one text
                        // block + N image_url blocks. Images ride as data:
                        // URLs (data:<wireMime>;base64,<payload>). Works on
                        // gpt-4o, gpt-4o-mini, gpt-4-turbo, and the o*
                        // reasoning models. DeepSeek inherits this adapter;
                        // vision is silently ignored by non-vision DeepSeek
                        // models.
                        val textBlock = """{"type":"text","text":${msg.content.toJsonString()}}"""
                        val imageBlocks = images.joinToString(",") { part ->
                            val dataUrl = "data:${part.wireMime.value};base64,${part.base64}"
                            """{"type":"image_url","image_url":{"url":${dataUrl.toJsonString()}}}"""
                        }
                        """{"role":"user","content":[$textBlock,$imageBlocks]}"""
                    } else {
                        """{"role":"user","content":${msg.content.toJsonString()}}"""
                    }
                }

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

                else -> error("Unknown LlmMessage role for $providerLabel: '${msg.role}'")
            }
        }

        val toolsField = if (tools.isNotEmpty()) {
            val defs = tools.joinToString(",") { t ->
                val schema = t.argsType?.jsonSchema()
                    ?: t.parametersSchemaJson
                    ?: """{"type":"object","properties":{},"additionalProperties":true}"""
                """{"type":"function","function":{"name":${t.name.toJsonString()},"description":${t.description.toJsonString()},"parameters":$schema}}"""
            }
            ""","tools":[$defs]"""
        } else ""

        // #1743: stream_options.include_usage opts into a final usage-only
        // delta after finish_reason — required to get TokenUsage on stream.
        val streamField = if (stream) ""","stream":true,"stream_options":{"include_usage":true}""" else ""
        val responseFormatField = jsonSchema?.let { schema ->
            ""","response_format":{"type":"json_schema","json_schema":{"name":${schema.wireName().toJsonString()},"schema":${schema.schema},"strict":true}}"""
        } ?: ""
        val additionalFields = additionalRequestJsonFields(stream = stream, jsonSchema = jsonSchema)
        // #2659 — `prompt_cache_key` routing hint for OpenAI's automatic
        // prefix caching. Same-key requests get routed to the same cache
        // shard, improving hit rate. Omitted when null.
        val cacheKeyField = promptCacheKey?.let { ""","prompt_cache_key":${it.toJsonString()}""" } ?: ""
        // #2479 part 2 — tool_choice wire mapping. Auto = field omitted
        // (provider default). None additionally drops the tools array (no
        // separate "tool_choice":"none" needed when tools aren't sent — but
        // we send "none" anyway so an operator inspecting the wire shape
        // sees an explicit signal).
        val toolChoiceField = when (val tc = toolChoice) {
            ToolChoice.Auto -> ""
            ToolChoice.Required -> ""","tool_choice":"required""""
            ToolChoice.None -> ""","tool_choice":"none""""
            is ToolChoice.Specific ->
                ""","tool_choice":{"type":"function","function":{"name":${tc.name.toJsonString()}}}"""
        }
        val effectiveToolsField = if (toolChoice == ToolChoice.None) "" else toolsField
        return """{"model":${model.toJsonString()},"max_tokens":$maxTokens,"temperature":$temperature$additionalFields$cacheKeyField$streamField,"messages":[${messageObjects.joinToString(",")}]$effectiveToolsField$toolChoiceField$responseFormatField}"""
    }

    protected open fun additionalRequestJsonFields(
        stream: Boolean,
        jsonSchema: JsonSchema?,
    ): String {
        // #2411 — reasoning_effort for reasoning-capable models when opted in.
        val effort = reasoning?.takeIf { it.enabled }?.effort
        return if (effort != null) ""","reasoning_effort":${effort.name.lowercase().toJsonString()}""" else ""
    }

    internal fun parseResponse(body: String): LlmResponse {
        val root = LenientJsonParser.parse(body) as? Map<*, *>
            ?: return LlmResponse.Text(body)

        // Provider-error envelope: OpenAI-compatible APIs return
        //   {"error":{"type":"...","message":"...","code":"..."}}
        // on 4xx/5xx. Surface as LlmProviderException — same contract as
        // OllamaClient (#702) and ClaudeClient (#1644).
        (root["error"] as? Map<*, *>)?.let { err ->
            val type = err["type"] as? String
            val message = err["message"] as? String
            throw LlmProviderException("$providerLabel returned an error: ${type ?: "unknown"}: ${message ?: "no message"}")
        }

        val tokenUsage = extractTokenUsage(root)
        val choices = root["choices"] as? List<*> ?: return LlmResponse.Text(body, tokenUsage)
        val choice = choices.firstOrNull() as? Map<*, *> ?: return LlmResponse.Text(body, tokenUsage)
        val message = choice["message"] as? Map<*, *> ?: return LlmResponse.Text(body, tokenUsage)

        // #2409/#2411 — OpenAI-compatible reasoning text arrives in
        // `reasoning_content` (DeepSeek reasoner; some OpenAI-compatible
        // gateways). OpenAI proper omits it → null. No effect when absent.
        val reasoningText = (message["reasoning_content"] as? String)?.ifEmpty { null }

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
            if (calls.isNotEmpty()) return LlmResponse.ToolCalls(calls, tokenUsage, reasoningText)
        }

        val content = message["content"] as? String ?: ""
        return LlmResponse.Text(content, tokenUsage, reasoningText)
    }

    private fun extractTokenUsage(root: Map<*, *>): TokenUsage? {
        val usage = root["usage"] as? Map<*, *> ?: return null
        return tokenUsageFromUsageMap(usage)
    }

    private fun tokenUsageFromUsageMap(usage: Map<*, *>): TokenUsage? {
        val prompt = (usage["prompt_tokens"] as? Number)?.toInt()
        val completion = (usage["completion_tokens"] as? Number)?.toInt()
        val details = usage["prompt_tokens_details"] as? Map<*, *>
        val cached = (details?.get("cached_tokens") as? Number)?.toInt()
        // #2411 — reasoning models report reasoning tokens (a subset of completion).
        val completionDetails = usage["completion_tokens_details"] as? Map<*, *>
        val reasoningTokens = (completionDetails?.get("reasoning_tokens") as? Number)?.toInt()
        return if (prompt != null && completion != null) {
            TokenUsage(
                promptTokens = prompt,
                completionTokens = completion,
                cachedInputTokens = cached,
                provider = providerName,
                model = model,
                reasoningTokens = reasoningTokens,
            )
        } else null
    }

    companion object {
        // Hotfix from #2850 — matched Claude's 5-minute floor. Long
        // agentic Sonnet turns (extended thinking, large outputs,
        // tool-heavy loops) were dying at 60s in production. Override
        // via `model { requestTimeout = ... }` when the tail is longer.
        val DEFAULT_REQUEST_TIMEOUT: Duration = 300.seconds
        val DEFAULT_CONNECT_TIMEOUT: Duration = 10.seconds
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 16L * 1024 * 1024
        const val DEFAULT_MAX_TOKENS: Int = 4096
    }
}

