package agents_engine.otel

import agents_engine.core.AgentRuntimeContext
import agents_engine.core.Decision
import agents_engine.core.PipelineEvent
import agents_engine.model.TokenUsage
import agents_engine.observability.InterceptorPoint
import agents_engine.observability.ObservabilityBridge
import agents_engine.runtime.events.AgentEvent
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context

class OtelBridge(
    private val tracer: Tracer,
) : ObservabilityBridge {

    private val agentSpans = linkedMapOf<String, Span>()
    private val turnSpans = linkedMapOf<String, Span>()
    private val toolSpans = linkedMapOf<String, Span>()
    private val pendingInterceptorEvents = mutableListOf<PendingInterceptorEvent>()
    private val finishedFallbackAgentKeys = linkedSetOf<String>()

    @Synchronized
    override fun onPipelineEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.ErrorOccurred -> {
                val span = mostRecentAgentSpan()
                    ?: startSpan("agent.invoke", event.agentName, null, event.runtimeContext)
                flushPendingInterceptorEvents(span)
                span.recordException(event.error)
                span.setStatus(StatusCode.ERROR, event.error.message ?: event.error::class.simpleName ?: "error")
                if (span !in agentSpans.values) {
                    span.end()
                    rememberFinishedFallback(event.agentName, event.runtimeContext)
                }
            }
            is PipelineEvent.ToolCalled -> {
                mostRecentAgentSpan()?.addEvent(
                    "agent.tool.called",
                    Attributes.builder()
                        .put("tool.name", event.toolName)
                        .put("tool.result.type", typeName(event.result) ?: "null")
                        .put("tool.policy.risk", event.toolPolicyRisk.manifestName)
                        .put("tool.used_declared_capability", event.usedDeclaredCapability)
                        .build(),
                )
            }
            is PipelineEvent.ToolDenied -> {
                // #2395 — a tool call blocked by an onBeforeToolCall Decision.Deny.
                mostRecentAgentSpan()?.addEvent(
                    "agent.tool.denied",
                    Attributes.builder()
                        .put("tool.name", event.toolName)
                        .put("tool.deny.reason", event.reason)
                        .put("tool.policy.risk", event.toolPolicyRisk.manifestName)
                        .put("tool.used_declared_capability", event.usedDeclaredCapability)
                        .build(),
                )
            }
            is PipelineEvent.KnowledgeLoaded -> {
                mostRecentAgentSpan()?.addEvent(
                    "agent.knowledge.loaded",
                    Attributes.builder()
                        .put("knowledge.name", event.entryName)
                        .put("knowledge.content_length", event.contentLength.toLong())
                        .build(),
                )
            }
            is PipelineEvent.SkillChosen -> {
                mostRecentAgentSpan()?.addEvent(
                    "agent.skill.chosen",
                    Attributes.of(AttributeKey.stringKey("agent.skill.name"), event.skillName),
                )
            }
            is PipelineEvent.BudgetThreshold -> {
                mostRecentAgentSpan()?.addEvent(
                    "agent.budget.threshold",
                    Attributes.builder()
                        .put("budget.reason", event.reason.name)
                        .put("budget.used_percent", event.usedPercent)
                        .build(),
                )
            }
            is PipelineEvent.ToolHallucinated -> {
                // #2757 — distinct from ToolDenied: the model emitted a name not
                // in the skill's allowlist. Recoverable per #2476; this span event
                // is the audit evidence for auditors filtering by event class.
                mostRecentAgentSpan()?.addEvent(
                    "agent.tool.hallucinated",
                    Attributes.builder()
                        .put("tool.requested_name", event.requestedName)
                        .put(
                            AttributeKey.stringArrayKey("tool.allowed_tools"),
                            event.allowedTools,
                        )
                        .build(),
                )
            }
            is PipelineEvent.ApprovalRequested -> {
                // #2489 — human approval requested; field-only (no body / PII).
                mostRecentAgentSpan()?.addEvent(
                    "agent.approval.requested",
                    Attributes.builder()
                        .put("approval.title", event.title)
                        .put("approval.has_body", event.hasBody)
                        .also { b -> event.timeoutMs?.let { b.put("approval.timeout_ms", it) } }
                        .build(),
                )
            }
            is PipelineEvent.ApprovalDecided -> {
                // #2489 — the resumed HumanDecision; pairs with ApprovalRequested.
                mostRecentAgentSpan()?.addEvent(
                    "agent.approval.decided",
                    Attributes.builder()
                        .put("approval.decision", event.decision)
                        .put("approval.has_payload", event.hasPayload)
                        .build(),
                )
            }
        }
    }

    @Synchronized
    override fun onAgentEvent(event: AgentEvent<*>) {
        when (event) {
            is AgentEvent.SkillStarted -> {
                val span = startSpan("agent.invoke", event.agentId, event.skillName, event.runtimeContext)
                    .setAttribute("gen_ai.operation.name", "agent")
                flushPendingInterceptorEvents(span)
                agentSpans[agentKey(event.agentId, event.skillName, event.runtimeContext)] = span
            }
            is AgentEvent.SkillCompleted -> {
                val key = agentKey(event.agentId, event.skillName, event.runtimeContext)
                val span = agentSpans.remove(key) ?: mostRecentAgentSpan()
                if (span != null) {
                    applyUsage(span, event.tokensUsed)
                    span.end()
                }
            }
            is AgentEvent.Completed<*> -> {
                val span = agentSpans.remove(agentKey(event.agentId, null, event.runtimeContext))
                    ?: mostRecentAgentSpan()
                if (span != null && span.isRecording) {
                    span.setAttribute("agent.output.type", typeName(event.output) ?: "null")
                    applyUsage(span, event.tokensUsed)
                    span.end()
                }
            }
            is AgentEvent.Failed -> {
                if (agentSpans.isEmpty() && turnSpans.isEmpty() && toolSpans.isEmpty()) {
                    if (finishedFallbackAgentKeys.remove(agentKey(event.agentId, null, event.runtimeContext))) {
                        return
                    }
                    val span = startSpan("agent.invoke", event.agentId, null, event.runtimeContext)
                    flushPendingInterceptorEvents(span)
                    span.recordException(event.cause)
                    span.setStatus(
                        StatusCode.ERROR,
                        event.cause.message ?: event.cause::class.simpleName ?: "error",
                    )
                    span.end()
                } else {
                    endAllForFailure(event.cause)
                }
            }
            is AgentEvent.ModelTurnStarted -> {
                val parent = activeAgentSpan(event.agentId, event.skillName, event.runtimeContext)
                val span = tracer.spanBuilder("gen_ai.chat")
                    .setParent(parent?.storeInContext(Context.current()) ?: Context.current())
                    .setAttribute("gen_ai.operation.name", "chat")
                    .setAttribute("gen_ai.system", event.provider)
                    .setAttribute("gen_ai.request.model", event.model)
                    .setAttribute("gen_ai.request.temperature", event.temperature)
                    .setAttribute("agent.name", event.agentId)
                    .setAttribute("agent.skill.name", event.skillName)
                    .setAttribute("agent.turn.index", event.turnIndex.toLong())
                    .startSpan()
                applyRuntimeContext(span, event.runtimeContext)
                turnSpans[turnKey(event.agentId, event.skillName, event.turnIndex, event.runtimeContext)] = span
            }
            is AgentEvent.ModelTurnCompleted -> {
                val key = turnKey(event.agentId, event.skillName, event.turnIndex, event.runtimeContext)
                val span = turnSpans.remove(key) ?: mostRecentTurnSpan() ?: return
                span.setAttribute("gen_ai.system", event.provider)
                span.setAttribute("gen_ai.request.model", event.model)
                span.setAttribute("gen_ai.response.type", event.responseType)
                applyUsage(span, event.tokensUsed)
                span.end()
            }
            is AgentEvent.Token -> {
                activeTurnSpan(event.agentId, event.skillName, event.runtimeContext)
                    ?.addEvent(
                        "gen_ai.token",
                        Attributes.builder()
                            .put("agent.skill.name", event.skillName)
                            .put("gen_ai.token.length", event.text.length.toLong())
                            .build(),
                    )
            }
            is AgentEvent.Reasoning -> {
                // #2406 — record reasoning length only (like tokens); the text is
                // high-volume / potentially sensitive and is not put on the span.
                activeTurnSpan(event.agentId, event.skillName, event.runtimeContext)
                    ?.addEvent(
                        "gen_ai.reasoning",
                        Attributes.builder()
                            .put("agent.skill.name", event.skillName)
                            .put("gen_ai.reasoning.length", event.text.length.toLong())
                            .build(),
                    )
            }
            is AgentEvent.ToolCallStarted -> {
                val parent = activeAgentSpan(event.agentId, event.skillName, event.runtimeContext)
                val span = tracer.spanBuilder("gen_ai.tool")
                    .setParent(parent?.storeInContext(Context.current()) ?: Context.current())
                    .setAttribute("gen_ai.operation.name", "tool")
                    .setAttribute("agent.name", event.agentId)
                    .setAttribute("agent.skill.name", event.skillName)
                    .setAttribute("agent.request.id", event.requestId)
                    .setAttribute("tool.call.id", event.callId)
                    .setAttribute("tool.name", event.toolName)
                    .startSpan()
                applyRuntimeContext(span, event.runtimeContext)
                toolSpans[toolKey(event.callId, event.runtimeContext)] = span
            }
            is AgentEvent.ToolCallArgumentsDelta -> {
                toolSpans[toolKey(event.callId, event.runtimeContext)]?.addEvent(
                    "tool.arguments.delta",
                    Attributes.of(
                        AttributeKey.longKey("tool.arguments.delta.length"),
                        event.deltaJson.length.toLong(),
                    ),
                )
            }
            is AgentEvent.ToolCallFinished -> {
                val span = toolSpans.remove(toolKey(event.callId, event.runtimeContext)) ?: return
                span.setAttribute("tool.name", event.toolName)
                span.setAttribute("tool.arguments.type", "Map")
                span.setAttribute("tool.result.type", typeName(event.result) ?: "null")
                span.setAttribute("tool.error", event.isError)
                if (event.isError) span.setStatus(StatusCode.ERROR, "tool call failed")
                span.end()
            }
        }
    }

    @Synchronized
    override fun onInterceptorDecision(point: InterceptorPoint, decision: Decision<*>) {
        val span = mostRecentToolSpan() ?: mostRecentAgentSpan()
        recordInterceptorDecision(span, point, decision)
    }

    private fun recordInterceptorDecision(span: Span?, point: InterceptorPoint, decision: Decision<*>) {
        if (span == null) {
            pendingInterceptorEvents += PendingInterceptorEvent(
                name = interceptorEventName(decision),
                attributes = interceptorAttributes(point),
                errorStatus = decision is Decision.Deny,
            )
            if (pendingInterceptorEvents.size > MAX_PENDING_INTERCEPTOR_EVENTS) {
                pendingInterceptorEvents.removeAt(0)
            }
            return
        }
        when (decision) {
            Decision.Proceed -> span.addEvent("interceptor.proceed", interceptorAttributes(point))
            is Decision.ProceedWith<*> -> span.addEvent("interceptor.proceed_with", interceptorAttributes(point))
            is Decision.Deny -> {
                span.addEvent("interceptor.deny", interceptorAttributes(point))
                span.setStatus(StatusCode.ERROR, "interceptor denied")
            }
            is Decision.Substitute<*> -> span.addEvent("interceptor.substitute", interceptorAttributes(point))
        }
    }

    private fun flushPendingInterceptorEvents(span: Span) {
        pendingInterceptorEvents.forEach { event ->
            span.addEvent(event.name, event.attributes)
            if (event.errorStatus) span.setStatus(StatusCode.ERROR, "interceptor denied")
        }
        pendingInterceptorEvents.clear()
    }

    private fun startSpan(
        spanName: String,
        agentId: String,
        skillName: String?,
        context: AgentRuntimeContext,
    ): Span {
        val builder = tracer.spanBuilder(spanName)
            .setParent(Context.current())
            .setAttribute("agent.name", agentId)
            .setAttribute("agent.request.id", context.requestId)
        skillName?.let { builder.setAttribute("agent.skill.name", it) }
        context.sessionId?.let { builder.setAttribute("agent.session.id", it) }
        context.manifestHash?.let { builder.setAttribute("agent.manifest.hash", it) }
        return builder.startSpan()
    }

    private fun applyRuntimeContext(span: Span, context: AgentRuntimeContext) {
        span.setAttribute("agent.request.id", context.requestId)
        context.sessionId?.let { span.setAttribute("agent.session.id", it) }
        context.manifestHash?.let { span.setAttribute("agent.manifest.hash", it) }
    }

    private fun applyUsage(span: Span, usage: TokenUsage?) {
        if (usage == null) return
        span.setAttribute("gen_ai.usage.input_tokens", usage.promptTokens.toLong())
        span.setAttribute("gen_ai.usage.output_tokens", usage.completionTokens.toLong())
        usage.cachedInputTokens?.let { span.setAttribute("gen_ai.usage.cached_input_tokens", it.toLong()) }
        span.setAttribute("gen_ai.system", usage.provider)
        span.setAttribute("gen_ai.request.model", usage.model)
    }

    private fun endAllForFailure(cause: Throwable) {
        mostRecentAgentSpan()?.let { flushPendingInterceptorEvents(it) }
        (toolSpans.values.toList() + turnSpans.values.toList() + agentSpans.values.toList()).forEach { span ->
            span.recordException(cause)
            span.setStatus(StatusCode.ERROR, cause.message ?: cause::class.simpleName ?: "error")
            span.end()
        }
        toolSpans.clear()
        turnSpans.clear()
        agentSpans.clear()
    }

    private fun activeAgentSpan(
        agentId: String,
        skillName: String?,
        context: AgentRuntimeContext,
    ): Span? =
        agentSpans[agentKey(agentId, skillName, context)]
            ?: agentSpans[agentKey(agentId, null, context)]
            ?: mostRecentAgentSpan()

    private fun activeTurnSpan(
        agentId: String,
        skillName: String,
        context: AgentRuntimeContext,
    ): Span? {
        val prefix = listOf(context.requestId, context.sessionId.orEmpty(), agentId, skillName).joinToString(":") + ":"
        return turnSpans.entries.lastOrNull { it.key.startsWith(prefix) }?.value
            ?: mostRecentTurnSpan()
            ?: activeAgentSpan(agentId, skillName, context)
    }

    private fun mostRecentAgentSpan(): Span? = agentSpans.values.lastOrNull()

    private fun mostRecentTurnSpan(): Span? = turnSpans.values.lastOrNull()

    private fun mostRecentToolSpan(): Span? = toolSpans.values.lastOrNull()

    private fun agentKey(
        agentId: String,
        skillName: String?,
        context: AgentRuntimeContext,
    ): String =
        listOf(context.requestId, context.sessionId.orEmpty(), agentId, skillName.orEmpty()).joinToString(":")

    private fun toolKey(callId: String, context: AgentRuntimeContext): String =
        listOf(context.requestId, context.sessionId.orEmpty(), callId).joinToString(":")

    private fun turnKey(
        agentId: String,
        skillName: String,
        turnIndex: Int,
        context: AgentRuntimeContext,
    ): String =
        listOf(context.requestId, context.sessionId.orEmpty(), agentId, skillName, turnIndex).joinToString(":")

    private fun interceptorAttributes(point: InterceptorPoint): Attributes =
        Attributes.of(AttributeKey.stringKey("interceptor.point"), point.name)

    private fun interceptorEventName(decision: Decision<*>): String =
        when (decision) {
            Decision.Proceed -> "interceptor.proceed"
            is Decision.ProceedWith<*> -> "interceptor.proceed_with"
            is Decision.Deny -> "interceptor.deny"
            is Decision.Substitute<*> -> "interceptor.substitute"
        }

    private fun typeName(value: Any?): String? =
        value?.javaClass?.name

    private fun rememberFinishedFallback(agentId: String, context: AgentRuntimeContext) {
        finishedFallbackAgentKeys += agentKey(agentId, null, context)
        while (finishedFallbackAgentKeys.size > MAX_FINISHED_FALLBACK_KEYS) {
            val first = finishedFallbackAgentKeys.firstOrNull() ?: break
            finishedFallbackAgentKeys.remove(first)
        }
    }

    private data class PendingInterceptorEvent(
        val name: String,
        val attributes: Attributes,
        val errorStatus: Boolean,
    )

    private companion object {
        const val MAX_PENDING_INTERCEPTOR_EVENTS = 32
        const val MAX_FINISHED_FALLBACK_KEYS = 32
    }
}
