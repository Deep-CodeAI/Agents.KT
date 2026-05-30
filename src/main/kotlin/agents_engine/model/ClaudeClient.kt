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
    /**
     * #2408 — opt-in extended thinking. When enabled, sends
     * `thinking:{type:enabled, budget_tokens}` (Anthropic forces temperature=1)
     * and surfaces thinking blocks as reasoning. Off when null.
     */
    private val reasoning: ReasoningConfig? = null,
    /**
     * #2479 part 2 — vendor-neutral tool-choice control. The agentic loop
     * passes this through from `agent.toolChoice`. Wire mapping:
     *   - [ToolChoice.Auto] → `{"type":"auto"}` (or field omitted)
     *   - [ToolChoice.Required] → `{"type":"any"}` (Anthropic spelling)
     *   - [ToolChoice.None] → drop `tools` from request (Anthropic has no
     *     "none" enum; the model can't call what it doesn't see)
     *   - [ToolChoice.Specific] → `{"type":"tool","name":...}`
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
                    "thinking_delta" -> {
                        // #2408 — extended-thinking text streams here; signature_delta
                        // (verification) arrives too and is intentionally ignored.
                        val thinking = delta["thinking"] as? String ?: return
                        collector.emit(LlmChunk.ReasoningDelta(thinking))
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

    /**
     * #2658 — Anthropic `cache_control` JSON for a [CacheHint]. Anthropic
     * supports two TTL values: ephemeral default (~5 min, no explicit
     * `ttl` field) and an explicit `"ttl": "1h"`. Any [CacheHint.ttl]
     * greater than 5 minutes maps to `"1h"`; smaller or null TTLs use
     * the default ephemeral form.
     */
    private fun cacheControlJson(hint: CacheHint): String {
        val ttlMinutes = hint.ttl?.inWholeMinutes ?: 0
        return if (ttlMinutes > 5L) {
            """"cache_control":{"type":"ephemeral","ttl":"1h"}"""
        } else {
            """"cache_control":{"type":"ephemeral"}"""
        }
    }

    /**
     * #2658 — emit Anthropic's `system` field. When any system message
     * carries a [CacheHint], render `system` as the array form with one
     * `{type:"text", text, cache_control?}` block per system message;
     * otherwise emit the legacy `"system":"<text>"` string form.
     */
    private fun buildSystemField(
        systemMessages: List<LlmMessage>,
        cacheControlForHint: (CacheHint) -> String?,
    ): String {
        if (systemMessages.isEmpty()) return ""
        val anyHinted = systemMessages.any { it.cacheHint != null }
        if (!anyHinted) {
            // Legacy form — single string, preserves byte-identical wire
            // for callers that didn't enable caching.
            val combined = systemMessages.joinToString("\n\n") { it.content }
            return ""","system":${combined.toJsonString()}"""
        }
        val blocks = systemMessages.map { msg ->
            val hint = msg.cacheHint
            val cc = if (hint != null) cacheControlForHint(hint) else null
            if (cc == null) {
                """{"type":"text","text":${msg.content.toJsonString()}}"""
            } else {
                """{"type":"text","text":${msg.content.toJsonString()},$cc}"""
            }
        }
        return ""","system":[${blocks.joinToString(",")}]"""
    }

    internal fun buildRequestJson(
        messages: List<LlmMessage>,
        stream: Boolean = false,
        jsonSchema: JsonSchema? = null,
    ): String {
        // #2658 — collect ALL system-role messages so the custom-cacheable
        // segments emitted by AgenticLoop (each as its own system-role
        // message with a CacheHint of segment=Custom) can be encoded as
        // additional items in Anthropic's `system` array.
        val systemMessages = messages.filter { it.role == "system" }
        val nonSystem = messages.filter { it.role != "system" }

        // #2658 — breakpoint accounting. Anthropic caps cache_control
        // markers at 4 per request; coalesce silently when over budget
        // (drop the rest, log once).
        var breakpointBudget = 4
        fun consumeBreakpoint(): Boolean {
            if (breakpointBudget <= 0) return false
            breakpointBudget--
            return true
        }

        // Synthesize stable tool_use ids in order across the conversation — one
        // counter per request. Tool-result messages consume ids in the same
        // order (FIFO over the running queue) so the wire pairing matches.
        val pendingToolUseIds: ArrayDeque<String> = ArrayDeque()
        var toolUseCounter = 0

        val messageObjects = nonSystem.map { msg ->
            // #2658 — when an assistant/user message carries a CacheHint
            // (typically segment=Conversation for rolling mode), attach
            // cache_control to the LAST content block on the wire.
            val cacheControl = if (msg.cacheHint != null && consumeBreakpoint()) cacheControlJson(msg.cacheHint) else null
            when (msg.role) {
                "user" -> {
                    val images = msg.images
                    if (!images.isNullOrEmpty()) {
                        // #2470 — vision input. Anthropic accepts a content
                        // array of typed blocks; one text block + N image
                        // blocks. Each image block is base64-source with a
                        // typed media_type.
                        val textBlock = """{"type":"text","text":${msg.content.toJsonString()}}"""
                        val imageBlocks = images.joinToString(",") { part ->
                            """{"type":"image","source":{"type":"base64","media_type":${part.wireMime.value.toJsonString()},"data":${part.base64.toJsonString()}}}"""
                        }
                        val allBlocks = "$textBlock,$imageBlocks"
                        val withCache = if (cacheControl != null) {
                            // Attach cache_control to the LAST block.
                            val splitAt = allBlocks.lastIndexOf("}")
                            allBlocks.substring(0, splitAt) + ",$cacheControl" + allBlocks.substring(splitAt)
                        } else {
                            allBlocks
                        }
                        """{"role":"user","content":[$withCache]}"""
                    } else if (cacheControl == null) {
                        """{"role":"user","content":${msg.content.toJsonString()}}"""
                    } else {
                        // Single text content block with cache_control attached.
                        """{"role":"user","content":[{"type":"text","text":${msg.content.toJsonString()},$cacheControl}]}"""
                    }
                }

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
                    // Append cache_control to the LAST block when this
                    // message carries a hint (rolling conversation breakpoint).
                    if (cacheControl != null) {
                        val last = blocks.removeAt(blocks.size - 1)
                        // Strip the closing brace and append cache_control.
                        blocks += last.removeSuffix("}") + ",$cacheControl}"
                    }
                    """{"role":"assistant","content":[${blocks.joinToString(",")}]}"""
                }

                "tool" -> {
                    val id = pendingToolUseIds.removeFirstOrNull()
                        ?: error("tool result with no preceding assistant tool_use to pair with")
                    val toolBlock = if (cacheControl == null) {
                        """{"type":"tool_result","tool_use_id":${id.toJsonString()},"content":${msg.content.toJsonString()}}"""
                    } else {
                        """{"type":"tool_result","tool_use_id":${id.toJsonString()},"content":${msg.content.toJsonString()},$cacheControl}"""
                    }
                    """{"role":"user","content":[$toolBlock]}"""
                }

                else -> error("Unknown LlmMessage role for Claude: '${msg.role}'")
            }
        }

        // #2658 — system field. With cache hints, emit as the array form so
        // each segment carries its own cache_control marker. The first
        // system message is the "main" prompt; subsequent ones are custom
        // cacheable segments registered via `caching { cacheable("id") {...} }`.
        val systemField = buildSystemField(systemMessages) { hint ->
            if (consumeBreakpoint()) cacheControlJson(hint) else null
        }
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
        // #2658 — attach cache_control to the LAST tool def when the main
        // system message carries a SystemPrompt-segment hint with
        // cacheToolDefs (default-on) — caches the tool-def block as part
        // of the prefix the next call can hit.
        val mainSystemHint = systemMessages.firstOrNull()?.cacheHint
        val toolsWithCacheMarker: List<String> = if (
            toolDefs.isNotEmpty() &&
            mainSystemHint != null &&
            mainSystemHint.segment == CacheSegment.SystemPrompt &&
            consumeBreakpoint()
        ) {
            val cc = cacheControlJson(mainSystemHint)
            // Append cache_control to the LAST tool def only — that marks
            // the cacheable end of the tool block.
            toolDefs.dropLast(1) + (toolDefs.last().removeSuffix("}") + ",$cc}")
        } else {
            toolDefs
        }
        // #2479 part 2 — when toolChoice = None, drop the tools field
        // entirely. Anthropic has no "none" enum; not exposing tools is the
        // equivalent (the model can't call what it doesn't see). Structured-
        // output decoding overrides this — the forced-tool path needs tools.
        val toolsField = when {
            toolChoice == ToolChoice.None && structuredSchema == null -> ""
            toolsWithCacheMarker.isNotEmpty() -> ""","tools":[${toolsWithCacheMarker.joinToString(",")}]"""
            else -> ""
        }
        // tool_choice precedence:
        //   1. structured-output decoding wins (forced internal tool — the
        //      typed-output contract depends on this)
        //   2. else map user-set ToolChoice through to Anthropic's vocabulary
        val toolChoiceField = if (structuredSchema != null) {
            ""","tool_choice":{"type":"tool","name":${STRUCTURED_OUTPUT_TOOL_NAME.toJsonString()}}"""
        } else when (val tc = toolChoice) {
            ToolChoice.Auto, ToolChoice.None -> ""
            ToolChoice.Required -> ""","tool_choice":{"type":"any"}"""
            is ToolChoice.Specific ->
                ""","tool_choice":{"type":"tool","name":${tc.name.toJsonString()}}"""
        }

        val streamField = if (stream) ""","stream":true""" else ""
        // #2408 — extended thinking. Anthropic requires temperature=1 when on;
        // budget_tokens must be >=1024 and < max_tokens.
        val thinkingOn = reasoning?.enabled == true
        val effectiveTemperature = if (thinkingOn) 1.0 else temperature
        val thinkingField = if (thinkingOn) {
            val budget = (reasoning?.budgetTokens ?: 1024).coerceIn(1024, (maxTokens - 1).coerceAtLeast(1024))
            ""","thinking":{"type":"enabled","budget_tokens":$budget}"""
        } else ""
        return """{"model":${model.toJsonString()},"max_tokens":$maxTokens,"temperature":$effectiveTemperature$thinkingField$streamField$systemField,"messages":[${messageObjects.joinToString(",")}]$toolsField$toolChoiceField}"""
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

        // #2408 — extended-thinking blocks carry the model's reasoning.
        val reasoningText = content
            .mapNotNull { it as? Map<*, *> }
            .filter { it["type"] == "thinking" }
            .joinToString("") { (it["thinking"] as? String) ?: "" }
            .ifEmpty { null }

        val toolUses = content.mapNotNull { it as? Map<*, *> }.filter { it["type"] == "tool_use" }
        if (jsonSchema != null) {
            val structured = toolUses.firstOrNull { it["name"] == STRUCTURED_OUTPUT_TOOL_NAME }
            if (structured != null) {
                val parsed = parseToolArguments(structured["input"])
                return LlmResponse.Text(InlineToolCallParser.argsToJson(parsed.arguments), tokenUsage, reasoningText)
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
            if (calls.isNotEmpty()) return LlmResponse.ToolCalls(calls, tokenUsage, reasoningText)
        }

        val text = content
            .mapNotNull { it as? Map<*, *> }
            .filter { it["type"] == "text" }
            .joinToString("") { (it["text"] as? String) ?: "" }
        return LlmResponse.Text(text, tokenUsage, reasoningText)
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

        // Hotfix from #2850 field report — 60s killed long Sonnet turns
        // (extended thinking, large outputs, tool-heavy loops, loaded API).
        // 5 minutes is the new floor; deployers can override via the
        // `model { requestTimeout = ... }` DSL when their tail is even
        // longer.
        val DEFAULT_REQUEST_TIMEOUT: Duration = 300.seconds
        val DEFAULT_CONNECT_TIMEOUT: Duration = 10.seconds
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 16L * 1024 * 1024

        // Anthropic requires max_tokens on every Messages request. 4096 is a
        // reasonable default for chat-shaped turns; callers tuning long-form
        // generation should set their own.
        const val DEFAULT_MAX_TOKENS: Int = 4096
    }
}

