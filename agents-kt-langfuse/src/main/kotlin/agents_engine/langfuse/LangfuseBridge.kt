package agents_engine.langfuse

import agents_engine.core.AgentRuntimeContext
import agents_engine.core.Decision
import agents_engine.core.PipelineEvent
import agents_engine.model.TokenUsage
import agents_engine.observability.InterceptorPoint
import agents_engine.observability.ObservabilityBridge
import agents_engine.runtime.events.AgentEvent
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.min

class LangfuseBridge internal constructor(
    private val sink: LangfuseIngestionSink,
    private val maxQueuedOperations: Int,
    private val batchSize: Int,
    private val logger: (message: String, cause: Throwable?) -> Unit,
    private val clock: Clock,
    private val idGenerator: () -> String,
) : ObservabilityBridge, AutoCloseable {

    constructor(
        publicKey: String,
        secretKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        maxQueuedOperations: Int = DEFAULT_MAX_QUEUED_OPERATIONS,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        logger: (message: String, cause: Throwable?) -> Unit = DEFAULT_LOGGER,
    ) : this(
        sink = LangfuseHttpIngestionSink(
            publicKey = publicKey,
            secretKey = secretKey,
            baseUrl = baseUrl,
        ),
        maxQueuedOperations = maxQueuedOperations,
        batchSize = batchSize,
        logger = logger,
        clock = Clock.systemUTC(),
        idGenerator = { UUID.randomUUID().toString() },
    )

    private val traces = linkedMapOf<String, TraceState>()
    private val generations = linkedMapOf<String, ObservationState>()
    private val toolSpans = linkedMapOf<String, ObservationState>()
    private val finishedFallbackTraceKeys = linkedSetOf<String>()
    private val pendingInterceptorDecisions = mutableListOf<PendingInterceptorDecision>()
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()
    private val queue = ArrayDeque<LangfuseIngestionEvent>()
    private var closed = false
    private var dispatching = false

    private val dispatcher = Thread(::dispatchLoop, "agents-kt-langfuse-dispatcher").apply {
        isDaemon = true
        start()
    }

    init {
        require(maxQueuedOperations >= 0) { "maxQueuedOperations must be >= 0" }
        require(batchSize > 0) { "batchSize must be > 0" }
    }

    @Synchronized
    override fun onPipelineEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.ErrorOccurred -> {
                val state = mostRecentTrace()
                    ?: startTrace(event.agentName, null, event.runtimeContext)
                enqueueEventObservation(
                    trace = state,
                    name = "agent.error",
                    input = mapOf("agent_id" to event.agentName),
                    metadata = metadata(event.runtimeContext, "error_type" to typeName(event.error)),
                    level = "ERROR",
                    statusMessage = event.error.message ?: event.error::class.simpleName ?: "error",
                )
                finishTraceWithError(state, event.error)
                traces.values.removeIf { it.traceId == state.traceId }
                rememberFinishedFallback(event.agentName, event.runtimeContext)
            }
            is PipelineEvent.BudgetThreshold -> {
                mostRecentTrace()?.let { state ->
                    enqueueEventObservation(
                        trace = state,
                        name = "agent.budget.threshold",
                        input = mapOf(
                            "reason" to event.reason.name,
                            "used_percent" to event.usedPercent,
                        ),
                        metadata = metadata(event.runtimeContext),
                    )
                }
            }
            is PipelineEvent.SkillChosen -> {
                mostRecentTrace()?.let { state ->
                    enqueueEventObservation(
                        trace = state,
                        name = "agent.skill.chosen",
                        input = mapOf("skill_name" to event.skillName),
                        metadata = metadata(event.runtimeContext),
                    )
                }
            }
            is PipelineEvent.KnowledgeLoaded -> {
                mostRecentTrace()?.let { state ->
                    enqueueEventObservation(
                        trace = state,
                        name = "agent.knowledge.loaded",
                        input = mapOf(
                            "entry_name" to event.entryName,
                            "content_length" to event.contentLength,
                        ),
                        metadata = metadata(event.runtimeContext),
                    )
                }
            }
            is PipelineEvent.ToolCalled -> {
                mostRecentTrace()?.let { state ->
                    enqueueEventObservation(
                        trace = state,
                        name = "agent.tool.called",
                        input = mapOf(
                            "tool_name" to event.toolName,
                            "result_type" to typeName(event.result),
                            "tool_policy_risk" to event.toolPolicyRisk.manifestName,
                            "used_declared_capability" to event.usedDeclaredCapability,
                        ),
                        metadata = metadata(event.runtimeContext),
                    )
                }
            }
            is PipelineEvent.ToolDenied -> {
                // #2395 — a tool call blocked by an onBeforeToolCall Decision.Deny.
                mostRecentTrace()?.let { state ->
                    enqueueEventObservation(
                        trace = state,
                        name = "agent.tool.denied",
                        input = mapOf(
                            "tool_name" to event.toolName,
                            "deny_reason" to event.reason,
                            "tool_policy_risk" to event.toolPolicyRisk.manifestName,
                            "used_declared_capability" to event.usedDeclaredCapability,
                        ),
                        metadata = metadata(event.runtimeContext),
                    )
                }
            }
            is PipelineEvent.ToolHallucinated -> {
                // #2757 — the model emitted a tool name not in the skill's allowlist.
                // Distinct from ToolDenied (policy rejection) — auditor wants to grep
                // by event class, not by error message body.
                mostRecentTrace()?.let { state ->
                    enqueueEventObservation(
                        trace = state,
                        name = "agent.tool.hallucinated",
                        input = mapOf(
                            "requested_name" to event.requestedName,
                            "allowed_tools" to event.allowedTools,
                        ),
                        metadata = metadata(event.runtimeContext),
                        level = "WARNING",
                    )
                }
            }
            is PipelineEvent.HandoffPerformed -> {
                // #3871 — names only, no payload.
                mostRecentTrace()?.let { state ->
                    enqueueEventObservation(
                        trace = state,
                        name = "agent.handoff",
                        input = mapOf(
                            "to_agent" to event.toAgent,
                            "decision_input_type" to event.decisionInputType,
                        ),
                        metadata = metadata(event.runtimeContext),
                    )
                }
            }
            is PipelineEvent.HistoryCompressed -> {
                // #3865 — counts only, no conversation content.
                mostRecentTrace()?.let { state ->
                    enqueueEventObservation(
                        trace = state,
                        name = "agent.history.compressed",
                        input = mapOf(
                            "replaced_count" to event.replacedCount,
                            "preserved_count" to event.preservedCount,
                            "digest_chars" to event.digestChars,
                        ),
                        metadata = metadata(event.runtimeContext),
                    )
                }
            }
            is PipelineEvent.ApprovalRequested -> {
                // #2489 — human approval pause. Field-only (no body / PII).
                mostRecentTrace()?.let { state ->
                    enqueueEventObservation(
                        trace = state,
                        name = "agent.approval.requested",
                        input = mapOf(
                            "title" to event.title,
                            "has_body" to event.hasBody,
                            "timeout_ms" to event.timeoutMs,
                        ),
                        metadata = metadata(event.runtimeContext),
                    )
                }
            }
            is PipelineEvent.ApprovalDecided -> {
                // #2489 — pairs with ApprovalRequested via timestamp ordering.
                mostRecentTrace()?.let { state ->
                    enqueueEventObservation(
                        trace = state,
                        name = "agent.approval.decided",
                        input = mapOf(
                            "decision" to event.decision,
                            "has_payload" to event.hasPayload,
                        ),
                        metadata = metadata(event.runtimeContext),
                    )
                }
            }
        }
    }

    @Synchronized
    override fun onAgentEvent(event: AgentEvent<*>) {
        when (event) {
            is AgentEvent.SkillStarted -> {
                val state = startTrace(event.agentId, event.skillName, event.runtimeContext)
                traces[traceKey(event.agentId, event.skillName, event.runtimeContext)] = state
            }
            is AgentEvent.SkillCompleted -> {
                val key = traceKey(event.agentId, event.skillName, event.runtimeContext)
                val state = traces.remove(key) ?: mostRecentTrace() ?: return
                finishTrace(
                    state = state,
                    output = mapOf(
                        "status" to "completed",
                        "token_usage" to usageMap(event.tokensUsed),
                    ),
                    metadataPairs = arrayOf("token_usage" to usageMap(event.tokensUsed)),
                )
            }
            is AgentEvent.Completed<*> -> {
                val state = traces.remove(traceKey(event.agentId, null, event.runtimeContext)) ?: return
                finishTrace(
                    state = state,
                    output = mapOf(
                        "output_type" to typeName(event.output),
                        "token_usage" to usageMap(event.tokensUsed),
                    ),
                    metadataPairs = arrayOf("token_usage" to usageMap(event.tokensUsed)),
                )
            }
            is AgentEvent.Failed -> {
                if (traces.isEmpty() && generations.isEmpty() && toolSpans.isEmpty()) {
                    if (finishedFallbackTraceKeys.remove(traceKey(event.agentId, null, event.runtimeContext))) {
                        return
                    }
                    val state = startTrace(event.agentId, null, event.runtimeContext)
                    finishTraceWithError(state, event.cause)
                } else {
                    finishAllWithError(event.cause)
                }
            }
            is AgentEvent.ModelTurnStarted -> {
                val trace = activeTrace(event.agentId, event.skillName, event.runtimeContext)
                val observationId = idGenerator()
                val body = observationBody(
                    id = observationId,
                    traceId = trace.traceId,
                    name = "${event.skillName}.model.${event.turnIndex}",
                    startTime = clock.instant(),
                    input = mapOf(
                        "provider" to event.provider,
                        "model" to event.model,
                        "temperature" to event.temperature,
                        "turn_index" to event.turnIndex,
                    ),
                    metadata = metadata(
                        event.runtimeContext,
                        "agent_id" to event.agentId,
                        "skill_name" to event.skillName,
                        "turn_index" to event.turnIndex,
                    ),
                ).also { map ->
                    map["model"] = event.model
                    map["modelParameters"] = mapOf(
                        "provider" to event.provider,
                        "temperature" to event.temperature,
                        "turn_index" to event.turnIndex,
                    )
                }
                enqueue("generation-create", body)
                generations[turnKey(event.agentId, event.skillName, event.turnIndex, event.runtimeContext)] =
                    ObservationState(observationId, trace.traceId, event.runtimeContext)
            }
            is AgentEvent.ModelTurnCompleted -> {
                val key = turnKey(event.agentId, event.skillName, event.turnIndex, event.runtimeContext)
                val state = generations.remove(key) ?: mostRecentGeneration() ?: return
                val body = observationBody(
                    id = state.observationId,
                    traceId = state.traceId,
                    endTime = clock.instant(),
                    output = mapOf("response_type" to event.responseType),
                    metadata = metadata(
                        event.runtimeContext,
                        "provider" to event.provider,
                        "model" to event.model,
                        "token_usage" to usageMap(event.tokensUsed),
                    ),
                ).also { map ->
                    map["model"] = event.model
                    usageMap(event.tokensUsed)?.let { usage ->
                        map["usage"] = usage
                        map["usageDetails"] = usageDetails(event.tokensUsed)
                    }
                }
                enqueue("generation-update", body)
            }
            is AgentEvent.Token -> {
                activeGeneration(event.agentId, event.skillName, event.runtimeContext)?.let { state ->
                    enqueueEventObservation(
                        traceId = state.traceId,
                        name = "llm.token",
                        input = mapOf("length" to event.text.length),
                        metadata = metadata(event.runtimeContext, "skill_name" to event.skillName),
                        parentObservationId = state.observationId,
                    )
                }
            }
            is AgentEvent.Reasoning -> {
                // #2406 — reasoning length only; text is high-volume / sensitive.
                activeGeneration(event.agentId, event.skillName, event.runtimeContext)?.let { state ->
                    enqueueEventObservation(
                        traceId = state.traceId,
                        name = "llm.reasoning",
                        input = mapOf("length" to event.text.length),
                        metadata = metadata(event.runtimeContext, "skill_name" to event.skillName),
                        parentObservationId = state.observationId,
                    )
                }
            }
            is AgentEvent.ToolCallStarted -> {
                val trace = activeTrace(event.agentId, event.skillName, event.runtimeContext)
                val observationId = event.callId.ifBlank { idGenerator() }
                val body = observationBody(
                    id = observationId,
                    traceId = trace.traceId,
                    name = "tool.${event.toolName}",
                    startTime = clock.instant(),
                    input = mapOf(
                        "call_id" to event.callId,
                        "tool_name" to event.toolName,
                    ),
                    metadata = metadata(
                        event.runtimeContext,
                        "agent_id" to event.agentId,
                        "skill_name" to event.skillName,
                        "tool_name" to event.toolName,
                        "call_id" to event.callId,
                    ),
                )
                enqueue("span-create", body)
                toolSpans[toolKey(event.callId, event.runtimeContext)] =
                    ObservationState(observationId, trace.traceId, event.runtimeContext)
            }
            is AgentEvent.ToolCallArgumentsDelta -> {
                toolSpans[toolKey(event.callId, event.runtimeContext)]?.let { state ->
                    enqueueEventObservation(
                        traceId = state.traceId,
                        name = "tool.arguments.delta",
                        input = mapOf("length" to event.deltaJson.length),
                        metadata = metadata(event.runtimeContext),
                        parentObservationId = state.observationId,
                    )
                }
            }
            is AgentEvent.ToolCallFinished -> {
                val state = toolSpans.remove(toolKey(event.callId, event.runtimeContext)) ?: return
                val body = observationBody(
                    id = state.observationId,
                    traceId = state.traceId,
                    name = "tool.${event.toolName}",
                    endTime = clock.instant(),
                    input = mapOf(
                        "args" to jsonValue(event.arguments),
                        "call_id" to event.callId,
                        "tool_name" to event.toolName,
                    ),
                    output = mapOf(
                        "result" to jsonValue(event.result),
                        "result_type" to typeName(event.result),
                        "is_error" to event.isError,
                    ),
                    metadata = metadata(event.runtimeContext, "tool_name" to event.toolName, "call_id" to event.callId),
                    level = if (event.isError) "ERROR" else null,
                    statusMessage = if (event.isError) "tool call failed" else null,
                )
                enqueue("span-update", body)
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
        val trace = mostRecentTrace()
        if (trace == null) {
            pendingInterceptorDecisions += PendingInterceptorDecision(point, tag)
            trimPendingInterceptorDecisions()
            return
        }
        trace.tags += tag
        enqueue(
            "trace-create",
            traceBody(
                id = trace.traceId,
                tags = trace.tags.toList(),
                metadata = metadata(trace.runtimeContext, "tags" to trace.tags.toList(), "interceptor_point" to point.name),
            ),
        )
        enqueueInterceptorDecisionEvent(trace, point, tag)
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

    private fun startTrace(agentId: String, skillName: String?, runtimeContext: AgentRuntimeContext): TraceState {
        val traceId = idGenerator()
        val pendingDecisions = pendingInterceptorDecisions.toList()
        pendingInterceptorDecisions.clear()
        val tags = linkedSetOf<String>().also { tags ->
            tags += pendingDecisions.map { it.tag }
        }
        val state = TraceState(traceId, runtimeContext, tags)
        enqueue(
            "trace-create",
            traceBody(
                id = traceId,
                timestamp = clock.instant(),
                name = skillName?.let { "$agentId.$it" } ?: agentId,
                sessionId = runtimeContext.sessionId,
                input = mapOf(
                    "agent_id" to agentId,
                    "skill_name" to skillName,
                    "request_id" to runtimeContext.requestId,
                    "session_id" to runtimeContext.sessionId,
                ),
                metadata = metadata(
                    runtimeContext,
                    "agent_id" to agentId,
                    "skill_name" to skillName,
                    "tags" to tags.toList(),
                ),
                tags = tags.toList(),
            ),
        )
        pendingDecisions.forEach { pending ->
            enqueueInterceptorDecisionEvent(state, pending.point, pending.tag)
        }
        return state
    }

    private fun finishTrace(
        state: TraceState,
        output: Map<String, Any?>,
        metadataPairs: Array<Pair<String, Any?>>,
    ) {
        enqueue(
            "trace-create",
            traceBody(
                id = state.traceId,
                output = output,
                metadata = metadata(state.runtimeContext, *metadataPairs),
                tags = state.tags.toList(),
            ),
        )
    }

    private fun finishTraceWithError(state: TraceState, cause: Throwable) {
        finishTrace(
            state = state,
            output = mapOf(
                "status" to "failed",
                "error" to (cause.message ?: cause::class.simpleName ?: "error"),
            ),
            metadataPairs = arrayOf("error_type" to typeName(cause)),
        )
    }

    private fun finishAllWithError(cause: Throwable) {
        toolSpans.values.toList().forEach { state ->
            enqueue(
                "span-update",
                observationBody(
                    id = state.observationId,
                    traceId = state.traceId,
                    endTime = clock.instant(),
                    level = "ERROR",
                    statusMessage = cause.message ?: cause::class.simpleName ?: "error",
                    metadata = metadata(state.runtimeContext, "error_type" to typeName(cause)),
                ),
            )
        }
        generations.values.toList().forEach { state ->
            enqueue(
                "generation-update",
                observationBody(
                    id = state.observationId,
                    traceId = state.traceId,
                    endTime = clock.instant(),
                    level = "ERROR",
                    statusMessage = cause.message ?: cause::class.simpleName ?: "error",
                    metadata = metadata(state.runtimeContext, "error_type" to typeName(cause)),
                ),
            )
        }
        traces.values.forEach { finishTraceWithError(it, cause) }
        toolSpans.clear()
        generations.clear()
        traces.clear()
    }

    private fun enqueueEventObservation(
        trace: TraceState,
        name: String,
        input: Map<String, Any?>,
        metadata: Map<String, Any?>,
        level: String? = null,
        statusMessage: String? = null,
        parentObservationId: String? = null,
    ) {
        enqueueEventObservation(
            traceId = trace.traceId,
            name = name,
            input = input,
            metadata = metadata,
            level = level,
            statusMessage = statusMessage,
            parentObservationId = parentObservationId,
        )
    }

    private fun enqueueEventObservation(
        traceId: String,
        name: String,
        input: Map<String, Any?>,
        metadata: Map<String, Any?>,
        level: String? = null,
        statusMessage: String? = null,
        parentObservationId: String? = null,
    ) {
        enqueue(
            "event-create",
            observationBody(
                id = idGenerator(),
                traceId = traceId,
                name = name,
                startTime = clock.instant(),
                input = input,
                metadata = metadata,
                level = level,
                statusMessage = statusMessage,
                parentObservationId = parentObservationId,
            ),
        )
    }

    private fun enqueueInterceptorDecisionEvent(trace: TraceState, point: InterceptorPoint, tag: String) {
        enqueueEventObservation(
            trace = trace,
            name = "interceptor.decision",
            input = mapOf("point" to point.name, "decision" to tag.removePrefix("interceptor:")),
            metadata = metadata(trace.runtimeContext, "tags" to trace.tags.toList()),
            level = if (tag == "interceptor:deny") "ERROR" else null,
            statusMessage = if (tag == "interceptor:deny") "interceptor denied" else null,
        )
    }

    private fun enqueue(type: String, body: Map<String, Any?>) {
        enqueue(
            LangfuseIngestionEvent(
                id = idGenerator(),
                type = type,
                timestamp = clock.instant(),
                body = body,
                metadata = mapOf("source" to "agents-kt"),
            ),
        )
    }

    private fun enqueue(event: LangfuseIngestionEvent) {
        synchronized(lock) {
            if (closed) {
                log("Langfuse bridge dropped operation after close", null)
                return@synchronized
            }
            if (maxQueuedOperations == 0) {
                log("Langfuse bridge dropped operation because buffering is disabled", null)
                return@synchronized
            }
            if (queue.size >= maxQueuedOperations) {
                queue.removeFirst()
                log("Langfuse bridge dropped oldest queued operation under backpressure", null)
            }
            queue.addLast(event)
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
                log("Langfuse bridge dropped ${batch.size} operation(s) after dispatch failure", t)
            } finally {
                synchronized(lock) {
                    dispatching = false
                    lock.notifyAll()
                }
            }
        }
    }

    private fun activeTrace(agentId: String, skillName: String?, context: AgentRuntimeContext): TraceState =
        traces[traceKey(agentId, skillName, context)]
            ?: traces[traceKey(agentId, null, context)]
            ?: mostRecentTrace()
            ?: startTrace(agentId, skillName, context)

    private fun activeGeneration(agentId: String, skillName: String, context: AgentRuntimeContext): ObservationState? {
        val prefix = listOf(context.requestId, context.sessionId.orEmpty(), agentId, skillName).joinToString(":") + ":"
        return generations.entries.lastOrNull { it.key.startsWith(prefix) }?.value ?: mostRecentGeneration()
    }

    private fun mostRecentTrace(): TraceState? = traces.values.lastOrNull()

    private fun mostRecentGeneration(): ObservationState? = generations.values.lastOrNull()

    private fun traceKey(agentId: String, skillName: String?, context: AgentRuntimeContext): String =
        listOf(context.requestId, context.sessionId.orEmpty(), agentId, skillName.orEmpty()).joinToString(":")

    private fun turnKey(agentId: String, skillName: String, turnIndex: Int, context: AgentRuntimeContext): String =
        listOf(context.requestId, context.sessionId.orEmpty(), agentId, skillName, turnIndex).joinToString(":")

    private fun toolKey(callId: String, context: AgentRuntimeContext): String =
        listOf(context.requestId, context.sessionId.orEmpty(), callId).joinToString(":")

    private fun traceBody(
        id: String,
        timestamp: Instant? = null,
        name: String? = null,
        input: Any? = null,
        output: Any? = null,
        sessionId: String? = null,
        metadata: Map<String, Any?>? = null,
        tags: List<String>? = null,
    ): MutableMap<String, Any?> =
        linkedMapOf<String, Any?>("id" to id).also { map ->
            if (timestamp != null) map["timestamp"] = timestamp.toString()
            if (name != null) map["name"] = name
            if (input != null) map["input"] = jsonValue(input)
            if (output != null) map["output"] = jsonValue(output)
            if (sessionId != null) map["sessionId"] = sessionId
            if (metadata != null) map["metadata"] = metadata
            if (tags != null) map["tags"] = tags
        }

    private fun observationBody(
        id: String,
        traceId: String,
        name: String? = null,
        startTime: Instant? = null,
        endTime: Instant? = null,
        input: Any? = null,
        output: Any? = null,
        metadata: Map<String, Any?>? = null,
        level: String? = null,
        statusMessage: String? = null,
        parentObservationId: String? = null,
    ): MutableMap<String, Any?> =
        linkedMapOf<String, Any?>(
            "id" to id,
            "traceId" to traceId,
        ).also { map ->
            if (name != null) map["name"] = name
            if (startTime != null) map["startTime"] = startTime.toString()
            if (endTime != null) map["endTime"] = endTime.toString()
            if (input != null) map["input"] = jsonValue(input)
            if (output != null) map["output"] = jsonValue(output)
            if (metadata != null) map["metadata"] = metadata
            if (level != null) map["level"] = level
            if (statusMessage != null) map["statusMessage"] = statusMessage
            if (parentObservationId != null) map["parentObservationId"] = parentObservationId
        }

    private fun metadata(context: AgentRuntimeContext, vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "agents_kt" to true,
            "request_id" to context.requestId,
            "session_id" to context.sessionId,
            "manifest_hash" to context.manifestHash,
        ).also { map ->
            pairs.forEach { (key, value) -> map[key] = jsonValue(value) }
        }

    private fun usageMap(usage: TokenUsage?): Map<String, Any?>? =
        usage?.let {
            linkedMapOf(
                "promptTokens" to it.promptTokens,
                "completionTokens" to it.completionTokens,
                "totalTokens" to it.total,
            )
        }

    private fun usageDetails(usage: TokenUsage?): Map<String, Any?>? =
        usage?.let {
            linkedMapOf(
                "input_tokens" to it.promptTokens,
                "output_tokens" to it.completionTokens,
                "total_tokens" to it.total,
            ).also { details ->
                val cachedInputTokens = it.cachedInputTokens
                if (cachedInputTokens != null) {
                    details["cached_input_tokens"] = cachedInputTokens
                }
            }
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

    private fun trimPendingInterceptorDecisions() {
        while (pendingInterceptorDecisions.size > MAX_PENDING_INTERCEPTOR_DECISIONS) {
            pendingInterceptorDecisions.removeAt(0)
        }
    }

    private fun rememberFinishedFallback(agentId: String, context: AgentRuntimeContext) {
        finishedFallbackTraceKeys += traceKey(agentId, null, context)
        while (finishedFallbackTraceKeys.size > MAX_FINISHED_FALLBACK_KEYS) {
            val first = finishedFallbackTraceKeys.firstOrNull() ?: break
            finishedFallbackTraceKeys.remove(first)
        }
    }

    private fun log(message: String, cause: Throwable?) {
        try {
            logger(message, cause)
        } catch (_: Throwable) {
            // Observability must never throw into the agent path.
        }
    }

    private data class TraceState(
        val traceId: String,
        val runtimeContext: AgentRuntimeContext,
        val tags: MutableSet<String> = linkedSetOf(),
    )

    private data class ObservationState(
        val observationId: String,
        val traceId: String,
        val runtimeContext: AgentRuntimeContext,
    )

    private data class PendingInterceptorDecision(
        val point: InterceptorPoint,
        val tag: String,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://cloud.langfuse.com"
        const val DEFAULT_MAX_QUEUED_OPERATIONS = 1_024
        const val DEFAULT_BATCH_SIZE = 64
        private const val MAX_JSON_DEPTH = 6
        private const val MAX_PENDING_INTERCEPTOR_DECISIONS = 32
        private const val MAX_FINISHED_FALLBACK_KEYS = 32
        private val JUL_LOGGER = Logger.getLogger(LangfuseBridge::class.java.name)
        val DEFAULT_LOGGER: (String, Throwable?) -> Unit = { message, cause ->
            if (cause == null) {
                JUL_LOGGER.warning(message)
            } else {
                JUL_LOGGER.log(Level.WARNING, message, cause)
            }
        }
    }
}
