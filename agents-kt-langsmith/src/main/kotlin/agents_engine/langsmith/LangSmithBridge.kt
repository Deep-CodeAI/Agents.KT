package agents_engine.langsmith

import agents_engine.core.AgentRuntimeContext
import agents_engine.core.Decision
import agents_engine.core.PipelineEvent
import agents_engine.model.TokenUsage
import agents_engine.observability.InterceptorPoint
import agents_engine.observability.ObservabilityBridge
import agents_engine.runtime.events.AgentEvent
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.min

class LangSmithBridge internal constructor(
    private val project: String,
    private val sink: LangSmithRunSink,
    private val maxQueuedOperations: Int,
    private val batchSize: Int,
    private val logger: (message: String, cause: Throwable?) -> Unit,
    private val clock: Clock,
    private val idGenerator: () -> String,
) : ObservabilityBridge, AutoCloseable {

    constructor(
        apiKey: String,
        project: String,
        baseUrl: String = DEFAULT_BASE_URL,
        workspaceId: String? = null,
        maxQueuedOperations: Int = DEFAULT_MAX_QUEUED_OPERATIONS,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        logger: (message: String, cause: Throwable?) -> Unit = DEFAULT_LOGGER,
    ) : this(
        project = project,
        sink = LangSmithHttpRunSink(
            apiKey = apiKey,
            baseUrl = baseUrl,
            workspaceId = workspaceId,
        ),
        maxQueuedOperations = maxQueuedOperations,
        batchSize = batchSize,
        logger = logger,
        clock = Clock.systemUTC(),
        idGenerator = { UUID.randomUUID().toString() },
    )

    private val agentRuns = linkedMapOf<String, RunState>()
    private val modelRuns = linkedMapOf<String, RunState>()
    private val toolRuns = linkedMapOf<String, RunState>()
    private val finishedFallbackAgentKeys = linkedSetOf<String>()
    private val pendingInterceptorTags = mutableListOf<String>()
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()
    private val queue = ArrayDeque<LangSmithRunOperation>()
    private var closed = false
    private var dispatching = false

    private val dispatcher = Thread(::dispatchLoop, "agents-kt-langsmith-dispatcher").apply {
        isDaemon = true
        start()
    }

    init {
        require(project.isNotBlank()) { "LangSmith project must not be blank" }
        require(maxQueuedOperations >= 0) { "maxQueuedOperations must be >= 0" }
        require(batchSize > 0) { "batchSize must be > 0" }
    }

    @Synchronized
    override fun onPipelineEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.ErrorOccurred -> {
                val state = mostRecentAgentRun()
                    ?: startAgentRun(event.agentName, null, event.runtimeContext)
                finishRunWithError(state, event.error)
                agentRuns.values.removeIf { it.runId == state.runId }
                rememberFinishedFallback(event.agentName, event.runtimeContext)
            }
            is PipelineEvent.BudgetThreshold -> {
                mostRecentAgentRun()?.let { state ->
                    enqueue(
                        LangSmithRunOperation.Update(
                            runId = state.runId,
                            patch = linkedMapOf(
                                "extra" to extra(
                                    event.runtimeContext,
                                    "budget" to linkedMapOf(
                                        "reason" to event.reason.name,
                                        "used_percent" to event.usedPercent,
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            }
            is PipelineEvent.SkillChosen -> {
                mostRecentAgentRun()?.let { state ->
                    enqueueEvent(state, "agent.skill.chosen", mapOf("skill_name" to event.skillName))
                }
            }
            is PipelineEvent.KnowledgeLoaded -> {
                mostRecentAgentRun()?.let { state ->
                    enqueueEvent(
                        state,
                        "agent.knowledge.loaded",
                        mapOf("entry_name" to event.entryName, "content_length" to event.contentLength),
                    )
                }
            }
            is PipelineEvent.ToolCalled -> {
                mostRecentAgentRun()?.let { state ->
                    enqueueEvent(
                        state,
                        "agent.tool.called",
                        mapOf(
                            "tool_name" to event.toolName,
                            "result_type" to typeName(event.result),
                            "tool_policy_risk" to event.toolPolicyRisk.manifestName,
                            "used_declared_capability" to event.usedDeclaredCapability,
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    override fun onAgentEvent(event: AgentEvent<*>) {
        when (event) {
            is AgentEvent.SkillStarted -> {
                val state = startAgentRun(event.agentId, event.skillName, event.runtimeContext)
                agentRuns[agentKey(event.agentId, event.skillName, event.runtimeContext)] = state
            }
            is AgentEvent.SkillCompleted -> {
                val key = agentKey(event.agentId, event.skillName, event.runtimeContext)
                val state = agentRuns.remove(key) ?: mostRecentAgentRun() ?: return
                enqueue(
                    LangSmithRunOperation.Update(
                        runId = state.runId,
                        patch = finishPatch(
                            outputs = linkedMapOf(
                                "status" to "completed",
                                "token_usage" to usageMap(event.tokensUsed),
                            ),
                            extra = extra(event.runtimeContext, "token_usage" to usageMap(event.tokensUsed)),
                        ),
                    ),
                )
            }
            is AgentEvent.Completed<*> -> {
                val state = agentRuns.remove(agentKey(event.agentId, null, event.runtimeContext)) ?: return
                enqueue(
                    LangSmithRunOperation.Update(
                        runId = state.runId,
                        patch = finishPatch(
                            outputs = linkedMapOf(
                                "output_type" to typeName(event.output),
                                "token_usage" to usageMap(event.tokensUsed),
                            ),
                            extra = extra(event.runtimeContext, "token_usage" to usageMap(event.tokensUsed)),
                        ),
                    ),
                )
            }
            is AgentEvent.Failed -> {
                if (agentRuns.isEmpty() && modelRuns.isEmpty() && toolRuns.isEmpty()) {
                    if (finishedFallbackAgentKeys.remove(agentKey(event.agentId, null, event.runtimeContext))) {
                        return
                    }
                    val state = startAgentRun(event.agentId, null, event.runtimeContext)
                    finishRunWithError(state, event.cause)
                } else {
                    finishAllWithError(event.cause)
                }
            }
            is AgentEvent.ModelTurnStarted -> {
                val parent = activeAgentRun(event.agentId, event.skillName, event.runtimeContext)
                val state = startChildRun(
                    parent = parent,
                    name = "${event.skillName}.model.${event.turnIndex}",
                    runType = "llm",
                    runtimeContext = event.runtimeContext,
                    inputs = linkedMapOf(
                        "messages" to emptyList<Any>(),
                        "provider" to event.provider,
                        "model" to event.model,
                        "temperature" to event.temperature,
                        "turn_index" to event.turnIndex,
                    ),
                    extraPairs = arrayOf(
                        "agent_id" to event.agentId,
                        "skill_name" to event.skillName,
                        "turn_index" to event.turnIndex,
                    ),
                )
                modelRuns[turnKey(event.agentId, event.skillName, event.turnIndex, event.runtimeContext)] = state
            }
            is AgentEvent.ModelTurnCompleted -> {
                val key = turnKey(event.agentId, event.skillName, event.turnIndex, event.runtimeContext)
                val state = modelRuns.remove(key) ?: mostRecentModelRun() ?: return
                enqueue(
                    LangSmithRunOperation.Update(
                        runId = state.runId,
                        patch = finishPatch(
                            outputs = linkedMapOf(
                                "response_type" to event.responseType,
                                "token_usage" to usageMap(event.tokensUsed),
                            ),
                            extra = extra(
                                event.runtimeContext,
                                "provider" to event.provider,
                                "model" to event.model,
                                "token_usage" to usageMap(event.tokensUsed),
                            ),
                        ),
                    ),
                )
            }
            is AgentEvent.Token -> {
                activeModelRun(event.agentId, event.skillName, event.runtimeContext)?.let { state ->
                    enqueueEvent(state, "llm.token", mapOf("length" to event.text.length))
                }
            }
            is AgentEvent.ToolCallStarted -> {
                val parent = activeAgentRun(event.agentId, event.skillName, event.runtimeContext)
                val state = startChildRun(
                    parent = parent,
                    name = event.toolName,
                    runType = "tool",
                    runtimeContext = event.runtimeContext,
                    inputs = linkedMapOf(
                        "call_id" to event.callId,
                        "tool_name" to event.toolName,
                    ),
                    extraPairs = arrayOf(
                        "agent_id" to event.agentId,
                        "skill_name" to event.skillName,
                        "tool_name" to event.toolName,
                        "call_id" to event.callId,
                    ),
                )
                toolRuns[toolKey(event.callId, event.runtimeContext)] = state
            }
            is AgentEvent.ToolCallArgumentsDelta -> {
                toolRuns[toolKey(event.callId, event.runtimeContext)]?.let { state ->
                    enqueueEvent(state, "tool.arguments.delta", mapOf("length" to event.deltaJson.length))
                }
            }
            is AgentEvent.ToolCallFinished -> {
                val state = toolRuns.remove(toolKey(event.callId, event.runtimeContext)) ?: return
                val patch = finishPatch(
                    outputs = linkedMapOf(
                        "result" to jsonValue(event.result),
                        "result_type" to typeName(event.result),
                        "is_error" to event.isError,
                    ),
                    inputs = linkedMapOf(
                        "args" to jsonValue(event.arguments),
                        "call_id" to event.callId,
                        "tool_name" to event.toolName,
                    ),
                    error = if (event.isError) "tool call failed" else null,
                    extra = extra(event.runtimeContext, "tool_name" to event.toolName, "call_id" to event.callId),
                )
                enqueue(LangSmithRunOperation.Update(state.runId, patch))
            }
        }
    }

    @Synchronized
    override fun onInterceptorDecision(point: InterceptorPoint, decision: Decision<*>) {
        val tag = when (decision) {
            Decision.Proceed -> "interceptor:proceed"
            is Decision.ProceedWith<*> -> "interceptor:proceed_with"
            is Decision.Deny -> "interceptor:deny"
            is Decision.Substitute<*> -> "interceptor:substitute"
        }
        val state = mostRecentToolRun() ?: mostRecentAgentRun()
        if (state == null) {
            pendingInterceptorTags += tag
            trimPendingInterceptorTags()
            return
        }
        state.tags += tag
        enqueue(
            LangSmithRunOperation.Update(
                runId = state.runId,
                patch = linkedMapOf(
                    "tags" to state.tags.toList(),
                    "extra" to extra(state.runtimeContext, "tags" to state.tags.toList(), "interceptor_point" to point.name),
                ),
            ),
        )
    }

    fun flush(timeoutMillis: Long = 5_000): Boolean =
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while ((queue.isNotEmpty() || dispatching) && System.currentTimeMillis() < deadline) {
                lock.wait(min(100L, deadline - System.currentTimeMillis()))
            }
            queue.isEmpty() && !dispatching
        }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        dispatcher.join(5_000)
    }

    private fun startAgentRun(
        agentId: String,
        skillName: String?,
        runtimeContext: AgentRuntimeContext,
    ): RunState {
        val runId = idGenerator()
        val startedAt = clock.instant()
        val tags = linkedSetOf<String>().also { tags ->
            tags += pendingInterceptorTags
            pendingInterceptorTags.clear()
        }
        val state = RunState(
            runId = runId,
            traceId = runId,
            dottedOrder = dottedOrder(startedAt, runId, null),
            runtimeContext = runtimeContext,
            tags = tags,
        )
        enqueue(
            LangSmithRunOperation.Create(
                run = baseRun(
                    state = state,
                    name = skillName?.let { "$agentId.$it" } ?: agentId,
                    runType = "chain",
                    startTime = startedAt,
                    inputs = linkedMapOf(
                        "agent_id" to agentId,
                        "skill_name" to skillName,
                        "request_id" to runtimeContext.requestId,
                        "session_id" to runtimeContext.sessionId,
                    ),
                    extra = extra(
                        runtimeContext,
                        "agent_id" to agentId,
                        "skill_name" to skillName,
                        "tags" to tags.toList(),
                    ),
                ),
            ),
        )
        return state
    }

    private fun startChildRun(
        parent: RunState?,
        name: String,
        runType: String,
        runtimeContext: AgentRuntimeContext,
        inputs: Map<String, Any?>,
        extraPairs: Array<Pair<String, Any?>>,
    ): RunState {
        val parentState = parent ?: startAgentRun("unknown-agent", null, runtimeContext)
        val runId = idGenerator()
        val startedAt = clock.instant()
        val state = RunState(
            runId = runId,
            traceId = parentState.traceId,
            dottedOrder = dottedOrder(startedAt, runId, parentState.dottedOrder),
            parentRunId = parentState.runId,
            runtimeContext = runtimeContext,
        )
        enqueue(
            LangSmithRunOperation.Create(
                run = baseRun(
                    state = state,
                    name = name,
                    runType = runType,
                    startTime = startedAt,
                    inputs = inputs,
                    extra = extra(runtimeContext, *extraPairs),
                ),
            ),
        )
        return state
    }

    private fun baseRun(
        state: RunState,
        name: String,
        runType: String,
        startTime: Instant,
        inputs: Map<String, Any?>,
        extra: Map<String, Any?>,
    ): Map<String, Any?> =
        linkedMapOf(
            "id" to state.runId,
            "trace_id" to state.traceId,
            "dotted_order" to state.dottedOrder,
            "parent_run_id" to state.parentRunId,
            "session_name" to project,
            "name" to name,
            "run_type" to runType,
            "inputs" to inputs,
            "start_time" to startTime.toString(),
            "extra" to extra,
            "tags" to state.tags.toList(),
        )

    private fun finishPatch(
        outputs: Map<String, Any?>,
        inputs: Map<String, Any?>? = null,
        error: String? = null,
        extra: Map<String, Any?>,
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "end_time" to clock.instant().toString(),
            "outputs" to outputs,
            "error" to error,
            "extra" to extra,
        ).also { patch ->
            if (inputs != null) patch["inputs"] = inputs
        }

    private fun finishRunWithError(state: RunState, cause: Throwable) {
        enqueue(
            LangSmithRunOperation.Update(
                runId = state.runId,
                patch = finishPatch(
                    outputs = linkedMapOf("status" to "failed"),
                    error = cause.message ?: cause::class.simpleName ?: "error",
                    extra = extra(state.runtimeContext, "error_type" to (cause::class.qualifiedName ?: cause::class.simpleName)),
                ),
            ),
        )
    }

    private fun finishAllWithError(cause: Throwable) {
        (toolRuns.values.toList() + modelRuns.values.toList() + agentRuns.values.toList()).forEach { state ->
            finishRunWithError(state, cause)
        }
        toolRuns.clear()
        modelRuns.clear()
        agentRuns.clear()
    }

    private fun enqueueEvent(state: RunState, name: String, values: Map<String, Any?>) {
        enqueue(
            LangSmithRunOperation.Update(
                runId = state.runId,
                patch = linkedMapOf(
                    "events" to listOf(
                        linkedMapOf(
                            "name" to name,
                            "time" to clock.instant().toString(),
                            "kwargs" to values,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun enqueue(operation: LangSmithRunOperation) {
        synchronized(lock) {
            if (closed) {
                log("LangSmith bridge dropped operation after close", null)
                return@synchronized
            }
            if (maxQueuedOperations == 0) {
                log("LangSmith bridge dropped operation because buffering is disabled", null)
                return@synchronized
            }
            if (queue.size >= maxQueuedOperations) {
                queue.removeFirst()
                log("LangSmith bridge dropped oldest queued operation under backpressure", null)
            }
            queue.addLast(operation)
            lock.notifyAll()
        }
    }

    private fun dispatchLoop() {
        while (true) {
            var shouldExit = false
            val batch = synchronized(lock) {
                while (queue.isEmpty() && !closed) {
                    lock.wait()
                }
                if (queue.isEmpty() && closed) {
                    shouldExit = true
                    emptyList()
                } else {
                    dispatching = true
                    val count = min(batchSize, queue.size)
                    List(count) { queue.removeFirst() }
                }
            }
            if (shouldExit) return
            try {
                sink.send(batch)
            } catch (t: Throwable) {
                log("LangSmith bridge dropped ${batch.size} operation(s) after dispatch failure", t)
            } finally {
                synchronized(lock) {
                    dispatching = false
                    lock.notifyAll()
                }
            }
        }
    }

    private fun activeAgentRun(
        agentId: String,
        skillName: String?,
        context: AgentRuntimeContext,
    ): RunState? =
        agentRuns[agentKey(agentId, skillName, context)]
            ?: agentRuns[agentKey(agentId, null, context)]
            ?: mostRecentAgentRun()

    private fun activeModelRun(agentId: String, skillName: String, context: AgentRuntimeContext): RunState? {
        val prefix = listOf(context.requestId, context.sessionId.orEmpty(), agentId, skillName).joinToString(":") + ":"
        return modelRuns.entries.lastOrNull { it.key.startsWith(prefix) }?.value ?: mostRecentModelRun()
    }

    private fun mostRecentAgentRun(): RunState? = agentRuns.values.lastOrNull()

    private fun mostRecentModelRun(): RunState? = modelRuns.values.lastOrNull()

    private fun mostRecentToolRun(): RunState? = toolRuns.values.lastOrNull()

    private fun agentKey(agentId: String, skillName: String?, context: AgentRuntimeContext): String =
        listOf(context.requestId, context.sessionId.orEmpty(), agentId, skillName.orEmpty()).joinToString(":")

    private fun turnKey(agentId: String, skillName: String, turnIndex: Int, context: AgentRuntimeContext): String =
        listOf(context.requestId, context.sessionId.orEmpty(), agentId, skillName, turnIndex).joinToString(":")

    private fun toolKey(callId: String, context: AgentRuntimeContext): String =
        listOf(context.requestId, context.sessionId.orEmpty(), callId).joinToString(":")

    private fun dottedOrder(startedAt: Instant, runId: String, parentDottedOrder: String?): String {
        val segment = DOTTED_ORDER_FORMAT.format(startedAt) + runId
        return parentDottedOrder?.let { "$it.$segment" } ?: segment
    }

    private fun extra(context: AgentRuntimeContext, vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "metadata" to linkedMapOf(
                "agents_kt" to true,
                "request_id" to context.requestId,
                "session_id" to context.sessionId,
                "manifest_hash" to context.manifestHash,
            ),
        ).also { map ->
            pairs.forEach { (key, value) -> map[key] = value }
        }

    private fun usageMap(usage: TokenUsage?): Map<String, Any?>? =
        usage?.let {
            linkedMapOf(
                "input_tokens" to it.promptTokens,
                "output_tokens" to it.completionTokens,
                "cached_input_tokens" to it.cachedInputTokens,
                "provider" to it.provider,
                "model" to it.model,
            )
        }

    private fun jsonValue(value: Any?, depth: Int = 0): Any? =
        when {
            depth >= MAX_JSON_DEPTH -> value?.toString()
            value == null -> null
            value is String || value is Number || value is Boolean -> value
            value is Map<*, *> -> value.entries.associate { (key, mapValue) ->
                key.toString() to jsonValue(mapValue, depth + 1)
            }
            value is Iterable<*> -> value.map { jsonValue(it, depth + 1) }
            value.javaClass.isArray -> (value as Array<*>).map { jsonValue(it, depth + 1) }
            else -> value.toString()
        }

    private fun typeName(value: Any?): String? = value?.javaClass?.name

    private fun trimPendingInterceptorTags() {
        while (pendingInterceptorTags.size > MAX_PENDING_INTERCEPTOR_TAGS) {
            pendingInterceptorTags.removeAt(0)
        }
    }

    private fun rememberFinishedFallback(agentId: String, context: AgentRuntimeContext) {
        finishedFallbackAgentKeys += agentKey(agentId, null, context)
        while (finishedFallbackAgentKeys.size > MAX_FINISHED_FALLBACK_KEYS) {
            val first = finishedFallbackAgentKeys.firstOrNull() ?: break
            finishedFallbackAgentKeys.remove(first)
        }
    }

    private fun log(message: String, cause: Throwable?) {
        try {
            logger(message, cause)
        } catch (_: Throwable) {
            // Observability must never throw into the agent path.
        }
    }

    private data class RunState(
        val runId: String,
        val traceId: String,
        val dottedOrder: String,
        val parentRunId: String? = null,
        val runtimeContext: AgentRuntimeContext,
        val tags: MutableSet<String> = linkedSetOf(),
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://api.smith.langchain.com"
        const val DEFAULT_MAX_QUEUED_OPERATIONS = 1_024
        const val DEFAULT_BATCH_SIZE = 64
        private const val MAX_JSON_DEPTH = 6
        private const val MAX_PENDING_INTERCEPTOR_TAGS = 32
        private const val MAX_FINISHED_FALLBACK_KEYS = 32
        private val DOTTED_ORDER_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSSSSS'Z'").withZone(ZoneOffset.UTC)
        private val JUL_LOGGER = Logger.getLogger(LangSmithBridge::class.java.name)
        val DEFAULT_LOGGER: (String, Throwable?) -> Unit = { message, cause ->
            if (cause == null) {
                JUL_LOGGER.warning(message)
            } else {
                JUL_LOGGER.log(Level.WARNING, message, cause)
            }
        }
    }
}

internal interface LangSmithRunSink {
    fun send(batch: List<LangSmithRunOperation>)
}

internal sealed interface LangSmithRunOperation {
    data class Create(val run: Map<String, Any?>) : LangSmithRunOperation
    data class Update(val runId: String, val patch: Map<String, Any?>) : LangSmithRunOperation
}

internal class LangSmithHttpRunSink(
    private val apiKey: String,
    baseUrl: String,
    private val workspaceId: String? = null,
    private val client: HttpClient = HttpClient.newHttpClient(),
) : LangSmithRunSink {

    private val endpoint = URI.create(baseUrl.trimEnd('/') + "/runs/batch")

    override fun send(batch: List<LangSmithRunOperation>) {
        if (batch.isEmpty()) return
        val creates = batch.filterIsInstance<LangSmithRunOperation.Create>().map { it.run }
        val updates = batch.filterIsInstance<LangSmithRunOperation.Update>().map { update ->
            linkedMapOf("id" to update.runId) + update.patch
        }
        val body = encodeJson(
            linkedMapOf(
                "post" to creates,
                "patch" to updates,
            ),
        )
        val requestBuilder = HttpRequest.newBuilder(endpoint)
            .header("content-type", "application/json")
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        workspaceId?.let { requestBuilder.header("x-tenant-id", it) }
        val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IOException("LangSmith batch ingest failed: HTTP ${response.statusCode()} ${response.body()}")
        }
    }
}

internal fun encodeJson(value: Any?): String =
    when (value) {
        null -> "null"
        is String -> "\"${escapeJson(value)}\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, mapValue) ->
            "\"${escapeJson(key.toString())}\":${encodeJson(mapValue)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { encodeJson(it) }
        else -> "\"${escapeJson(value.toString())}\""
    }

private fun escapeJson(value: String): String =
    buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch < ' ') {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }
