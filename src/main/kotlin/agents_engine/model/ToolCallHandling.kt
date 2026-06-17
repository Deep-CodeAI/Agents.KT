package agents_engine.model

import agents_engine.core.Agent
import agents_engine.core.AgentRuntimeContext
import agents_engine.core.Decision
import agents_engine.core.SessionSnapshot
import agents_engine.core.Skill
import agents_engine.core.withAgentRuntimeContext

/**
 * `agents_engine/model/ToolCallHandling.kt` — #2791 — the `LlmResponse.ToolCalls` arm of
 * [executeAgentic], lifted out so the loop body reads as orchestration. [handleToolCalls] owns the
 * per-call budget accounting (tool-call cap, consecutive-same-tool cap #969) and delegates the
 * dispatch of each individual call to [dispatchToolCall]; the cohesive sub-paths — unknown/
 * hallucinated tool (#2476/#2757), HITL interrupt (#2488/#2489), and outcome eventing
 * (#1739/#2395) — are each their own named, testable unit.
 *
 * Behavior-preserving: every counter, listener, audit event, and ordering matches the pre-#2791
 * inline branch byte-for-byte. The budget caps route through [BudgetTracker.resolveCapDecision]; a
 * snapshot is built lazily via [BudgetTracker.snapshot] only on the interrupt path.
 */
internal suspend fun <IN> handleToolCalls(
    response: LlmResponse.ToolCalls,
    agent: Agent<IN, *>,
    skill: Skill<*, *>,
    budget: BudgetConfig,
    emitter: AgentEventEmitter?,
    runtimeContext: AgentRuntimeContext,
    messages: MutableList<LlmMessage>,
    allowedToolMap: Map<String, ToolDef>,
    knowledgeToolMap: Map<String, ToolDef>,
    constraintTracker: ToolConstraintTracker,
    tracker: BudgetTracker,
    onTurnCheckpoint: ((SessionSnapshot) -> Unit)?,
) {
    // #2656 — Rolling conversation: anchor a cache breakpoint at each turn boundary so the growing
    // prefix keeps hitting. Off by default; opt-in via `caching { cacheConversation = Rolling }`.
    val convHint = if (
        agent.cacheConfig.enabled && agent.cacheConfig.cacheConversation == CacheConversation.Rolling
    ) {
        CacheHint(segment = CacheSegment.Conversation, ttl = agent.cacheConfig.ttl)
    } else null
    messages.add(LlmMessage("assistant", "", response.calls, cacheHint = convHint))

    for (call in response.calls) {
        if (tracker.toolCalls >= tracker.toolCallLimit) {
            // #2412/#2749 — give an onBudgetExceeded handler the chance to raise the cap or
            // Checkpoint-and-resume instead of throwing.
            tracker.resolveCapDecision(
                BudgetReason.TOOL_CALLS,
                tracker.toolCallLimit,
                "Agent '${agent.name}' exceeded tool-call budget of ${tracker.toolCallLimit}",
            ) { newLimit -> tracker.toolCallLimit = newLimit }
        }
        tracker.toolCalls++
        tracker.maybeFireThreshold(BudgetReason.TOOL_CALLS, tracker.toolCalls.toDouble() / tracker.toolCallLimit)
        tracker.recordConsecutiveTool(call.name)
        tracker.consecutiveSameToolLimit?.let { cap ->
            if (tracker.consecutiveSameTool > cap) {
                tracker.resolveCapDecision(
                    BudgetReason.CONSECUTIVE_TOOL,
                    cap,
                    "Agent '${agent.name}' invoked tool '${call.name}' " +
                        "${tracker.consecutiveSameTool} times in a row (cap: $cap)",
                    // Pre-#2791 behavior: this cap never re-armed its threshold.
                    rearmThreshold = false,
                ) { newLimit -> tracker.consecutiveSameToolLimit = newLimit }
            }
        }
        val toolMessage = dispatchToolCall(
            call, agent, emitter, runtimeContext, budget,
            allowedToolMap, knowledgeToolMap, constraintTracker, skill, tracker, onTurnCheckpoint,
        )
        messages.add(LlmMessage("tool", toolMessage))
    }
}

/**
 * Dispatch one tool [call] and return the message text to append as its `tool` result. Handles the
 * usage-constraint gate (#4490), `onBeforeToolCall` interceptor (#1907), and `Substitute`/`Deny`
 * decisions; an unknown tool short-circuits via [handleUnknownTool], and a HITL interrupt throws via
 * [buildInterrupt]. Untrusted tool output is wrapped (#642) on the success path.
 */
@Suppress("LongParameterList")
private suspend fun <IN> dispatchToolCall(
    call: ToolCall,
    agent: Agent<IN, *>,
    emitter: AgentEventEmitter?,
    runtimeContext: AgentRuntimeContext,
    budget: BudgetConfig,
    allowedToolMap: Map<String, ToolDef>,
    knowledgeToolMap: Map<String, ToolDef>,
    constraintTracker: ToolConstraintTracker,
    skill: Skill<*, *>,
    tracker: BudgetTracker,
    onTurnCheckpoint: ((SessionSnapshot) -> Unit)?,
): String {
    val isKnowledge = call.name in knowledgeToolMap
    val tool = allowedToolMap[call.name]
        ?: return handleUnknownTool(call, agent, skill, emitter, allowedToolMap)

    var effectiveCall = call
    var denied = false
    var deniedReason: String? = null
    // #4490 — usage constraints gate dispatch before interceptors: a violation denies through the
    // standard auditable path and the model sees the reason as the tool result (self-correctable).
    val constraintViolation = constraintTracker.violationFor(tool)
    val result = try {
        if (constraintViolation != null) {
            denied = true
            deniedReason = constraintViolation
            ToolResultRendering.formatDeniedToolError(call.name, constraintViolation)
        } else when (val decision = agent.decideBeforeToolCall(call.name, call.arguments)) {
            Decision.Proceed -> executeToolWithBudgetHandlingEvents(agent, tool, effectiveCall, budget, emitter)
            is Decision.ProceedWith -> {
                effectiveCall = call.copy(
                    arguments = decision.replacement,
                    rawArguments = null,
                    invalidArgumentsError = null,
                )
                executeToolWithBudgetHandlingEvents(agent, tool, effectiveCall, budget, emitter)
            }
            is Decision.Deny -> {
                denied = true
                deniedReason = decision.reason
                ToolResultRendering.formatDeniedToolError(call.name, decision.reason)
            }
            is Decision.Substitute<*> -> decision.result
        }
    } catch (signal: agents_engine.core.PendingInterruptSignal) {
        throw buildInterrupt(signal, effectiveCall, agent, runtimeContext, tracker, onTurnCheckpoint)
    }

    if (!denied) {
        // #4490 — count the dispatch and (post-result) the completion.
        constraintTracker.recordDispatch(effectiveCall.name)
        constraintTracker.recordCompletion(effectiveCall.name)
    }
    emitToolOutcome(agent, call, effectiveCall, result, denied, deniedReason, isKnowledge, runtimeContext, emitter)

    val rendered = ToolResultRendering.renderToolResultForLlm(result)
    return if (!denied && tool.untrustedOutput) {
        ToolResultRendering.wrapUntrustedToolResult(tool.name, rendered)
    } else {
        rendered
    }
}

/**
 * #2476 — the LLM emitted a tool name outside the skill's allowed set (hallucinated, or a tool from
 * a different skill on the same agent). Don't throw — that kills the loop and the model never gets to
 * retry. Fire the first-class hallucination audit signal (#2757, before the recovery message enters
 * context), surface a streaming error event, and return a recovery message naming the allowed tools so
 * the model can self-correct next turn. Allowed list is bounded by the skill, not the wider toolMap.
 */
private fun <IN> handleUnknownTool(
    call: ToolCall,
    agent: Agent<IN, *>,
    skill: Skill<*, *>,
    emitter: AgentEventEmitter?,
    allowedToolMap: Map<String, ToolDef>,
): String {
    val allowedList = allowedToolMap.keys.toList()
    val unknownToolMessage =
        "ERROR: Tool '${call.name}' is unknown for skill '${skill.name}'. " +
            "Allowed tools: ${allowedList.joinToString(", ")}. " +
            "Pick one of the allowed tools or return a final text answer."
    agent.toolHallucinatedListener?.invoke(call.name, call.arguments, allowedList)
    emitToolFinished(emitter, agent, call, unknownToolMessage, isError = true)
    return unknownToolMessage
}

/**
 * Fire the post-dispatch listeners + streaming event for one completed call. A denied call never
 * reaches `onToolUse`, so it fires `onToolDenied` instead (#2395 — what `observe{}` turns into
 * `PipelineEvent.ToolDenied`); a knowledge tool fires `onKnowledgeUsed`. All run under the runtime
 * context so requestId/sessionId/manifestHash correlate.
 */
@Suppress("LongParameterList")
private suspend fun <IN> emitToolOutcome(
    agent: Agent<IN, *>,
    call: ToolCall,
    effectiveCall: ToolCall,
    result: Any?,
    denied: Boolean,
    deniedReason: String?,
    isKnowledge: Boolean,
    runtimeContext: AgentRuntimeContext,
    emitter: AgentEventEmitter?,
) {
    if (denied) {
        withAgentRuntimeContext(runtimeContext) {
            agent.toolDeniedListener?.invoke(effectiveCall.name, effectiveCall.arguments, deniedReason ?: "")
        }
        emitToolFinished(emitter, agent, effectiveCall, result, isError = true)
    } else {
        if (isKnowledge) {
            withAgentRuntimeContext(runtimeContext) {
                agent.knowledgeUsedListener?.invoke(call.name, result?.toString() ?: "")
            }
        } else {
            withAgentRuntimeContext(runtimeContext) {
                agent.toolUseListener?.invoke(call.name, effectiveCall.arguments, result)
            }
        }
        emitToolFinished(emitter, agent, effectiveCall, result, isError = false)
    }
}

/**
 * #2488 — a tool called `humanApproval { }` (or otherwise raised a [PendingInterruptSignal]). Build
 * the snapshot at the pre-tool-result boundary (the assistant tool-calls turn is in `messages`, but
 * not a tool result for this call — it is synthesised on resume from `resumeWith`), fire the #2489
 * approval audit event if the payload is an [ApprovalRequest], deliver the snapshot via
 * `onTurnCheckpoint`, and return the [AgentInterruptException] for the caller to throw.
 */
private suspend fun <IN> buildInterrupt(
    signal: agents_engine.core.PendingInterruptSignal,
    effectiveCall: ToolCall,
    agent: Agent<IN, *>,
    runtimeContext: AgentRuntimeContext,
    tracker: BudgetTracker,
    onTurnCheckpoint: ((SessionSnapshot) -> Unit)?,
): agents_engine.core.AgentInterruptException {
    val payload = signal.payload
    if (payload is agents_engine.core.ApprovalRequest) {
        withAgentRuntimeContext(runtimeContext) {
            agent.approvalRequestedListener?.invoke(
                payload.title,
                payload.body != null,
                payload.timeout?.inWholeMilliseconds,
            )
        }
    }
    val snapshot = tracker.snapshot(effectiveCall.callId ?: "interrupt-${tracker.turns}-${tracker.toolCalls}")
    onTurnCheckpoint?.invoke(snapshot)
    return agents_engine.core.AgentInterruptException(
        snapshot = snapshot,
        payload = signal.payload,
        pendingToolCallId = snapshot.pendingInterruptCallId,
    )
}
