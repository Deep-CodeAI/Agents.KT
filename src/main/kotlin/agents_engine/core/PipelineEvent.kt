package agents_engine.core

import agents_engine.model.BudgetReason
import java.time.Instant

/**
 * `agents_engine/core/PipelineEvent.kt` — the post-hoc observability event
 * union and the [Agent.observe] extension that wires it into the four
 * per-event listener slots. See `src/main/resources/internals-agent/core/PipelineEvent.md`
 * for the adjunct surfaced to IDE-side LLM tools via `agents-kt-internals`
 * (#1837 / #1842).
 */

/**
 * Typed event union emitted via [Agent.observe]. Bridges the four existing
 * agent-level hooks (`onSkillChosen`, `onToolUse`, `onKnowledgeUsed`,
 * `onError`) into a single sealed type so integrators wiring telemetry can
 * use one `when` block instead of four separate registrations. See #965.
 *
 * The event surface is intentionally a SUBSET of the full PRD §10.2 hierarchy
 * — `TextDelta`, `SubAgentSpawned`, `ContextCompacted`, `Pipeline*`,
 * `Inference*` events depend on infrastructure that isn't shipped yet
 * (streaming, sub-agents, sessions, pipeline-level event sources). Those land
 * in follow-ups as the underlying machinery arrives.
 *
 * `agentName` and `timestamp` are present on every variant so consumers can
 * sort, filter, and attribute events without inspecting the variant. Runtime
 * context fields correlate the event with a request/session and, when
 * available, the static permission manifest that approved this agent shape.
 */
sealed interface PipelineEvent {
    val agentName: String
    val timestamp: Instant
    val runtimeContext: AgentRuntimeContext
    val requestId: String get() = runtimeContext.requestId
    val sessionId: String? get() = runtimeContext.sessionId
    val manifestHash: String? get() = runtimeContext.manifestHash

    data class SkillChosen(
        override val agentName: String,
        override val timestamp: Instant,
        val skillName: String,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : PipelineEvent

    data class ToolCalled(
        override val agentName: String,
        override val timestamp: Instant,
        val toolName: String,
        val arguments: Map<String, Any?>,
        val result: Any?,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
        val toolPolicyRisk: ToolRisk = ToolRisk.UNKNOWN,
        val usedDeclaredCapability: Boolean = false,
    ) : PipelineEvent

    /**
     * A tool call blocked by an `onBeforeToolCall` [Decision.Deny] (#2395).
     * Emitted via [Agent.onToolDenied] so audit/observability sees blocked
     * attempts that never reach [ToolCalled] (which rides [Agent.onToolUse],
     * and the executor never ran). `reason` is the denial reason surfaced to
     * the model.
     */
    data class ToolDenied(
        override val agentName: String,
        override val timestamp: Instant,
        val toolName: String,
        val arguments: Map<String, Any?>,
        val reason: String,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
        val toolPolicyRisk: ToolRisk = ToolRisk.UNKNOWN,
        val usedDeclaredCapability: Boolean = false,
    ) : PipelineEvent

    data class KnowledgeLoaded(
        override val agentName: String,
        override val timestamp: Instant,
        val entryName: String,
        val contentLength: Int,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : PipelineEvent

    data class ErrorOccurred(
        override val agentName: String,
        override val timestamp: Instant,
        val error: Throwable,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : PipelineEvent

    data class BudgetThreshold(
        override val agentName: String,
        override val timestamp: Instant,
        val reason: BudgetReason,
        val usedPercent: Double,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : PipelineEvent

    /**
     * #2757 — the model emitted a tool name that is NOT in the active skill's
     * allowlist (hallucinated, or a tool that belongs to a different skill on
     * the same agent). The runtime appends a tool-result error to context and
     * continues (per #2476), but this event is first-class audit evidence:
     * "the model tried to call X" is distinct from "tool X failed" or "policy
     * denied X" or "model returned text Y." Auditors can grep by event class.
     *
     * `allowedTools` is the skill's allowlist, same set the recovery message
     * names — does NOT leak the wider `agent.toolMap`.
     */
    data class ToolHallucinated(
        override val agentName: String,
        override val timestamp: Instant,
        val requestedName: String,
        val arguments: Map<String, Any?>,
        val allowedTools: List<String>,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : PipelineEvent

    /**
     * #3865 Phase 1 — the history-compression pass replaced part of the
     * conversation with a digest before a model turn. `replacedCount` /
     * `preservedCount` describe the swap; `digestChars` sizes the summary
     * without copying potentially sensitive conversation content into the
     * audit row. Original turn content is recoverable from the session
     * stream that preceded this event, not from this row.
     */
    data class HistoryCompressed(
        override val agentName: String,
        override val timestamp: Instant,
        val replacedCount: Int,
        val preservedCount: Int,
        val digestChars: Int,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : PipelineEvent

    /**
     * #2489 — a tool inside the agentic loop called `humanApproval { }` and
     * the runtime is about to pause for human input. Emitted before the
     * [AgentInterruptException] is thrown, so audit consumers see the request
     * on the same wall-clock ordering as the snapshot capture. Field-only
     * — `title` is the rendered prompt; `hasBody` indicates whether
     * additional context (typed plan, artefact) accompanied the request,
     * without copying the body into the audit row (which may be high-volume
     * or PII-sensitive). `timeoutMs` is the advisory wall-clock cap the
     * caller should honour.
     */
    data class ApprovalRequested(
        override val agentName: String,
        override val timestamp: Instant,
        val title: String,
        val hasBody: Boolean,
        val timeoutMs: Long?,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : PipelineEvent

    /**
     * #2489 — the resume path observed a [HumanDecision] in `resumeWith`,
     * synthesised the tool result, and is about to continue the loop.
     * `decision` is the simple class name of the [HumanDecision] variant
     * (Approved / Rejected / Edited / Responded) — `hasPayload` indicates
     * whether the Edited/Responded variant carried a non-null payload.
     * The payload itself stays off the audit row (same PII discipline as
     * [ApprovalRequested.hasBody]).
     */
    data class ApprovalDecided(
        override val agentName: String,
        override val timestamp: Instant,
        val decision: String,
        val hasPayload: Boolean,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : PipelineEvent
}

/**
 * Register a unified event handler for an agent's runtime lifecycle. Composes
 * with any prior listeners (theirs fire first, then the handler observes the
 * event) — multiple `observe { }` calls stack additively rather than replacing
 * earlier ones.
 *
 * Safe to call after `agent { }` construction has frozen the agent: listener
 * slots are intentionally exempt from the freeze (see Agent.kt — listeners
 * are designed to be settable post-construction for instrumentation).
 *
 * Variants are emitted as the underlying hooks fire:
 * - [PipelineEvent.SkillChosen] — when the agent picks a skill (see [Agent.onSkillChosen])
 * - [PipelineEvent.ToolCalled] — when an action tool returns (see [Agent.onToolUse])
 * - [PipelineEvent.KnowledgeLoaded] — when a knowledge entry is fetched (see [Agent.onKnowledgeUsed])
 * - [PipelineEvent.ErrorOccurred] — when an exception is about to propagate out (see [Agent.onError])
 * - [PipelineEvent.BudgetThreshold] — when a budget crosses [Agent.onBudgetThreshold]'s threshold
 * - [PipelineEvent.ToolHallucinated] — when the model emits a tool name not in the skill's allowlist (#2757)
 * - [PipelineEvent.ApprovalRequested] — when a tool calls `humanApproval { }` (#2489)
 * - [PipelineEvent.ApprovalDecided] — when resume synthesises a result from a [HumanDecision] (#2489)
 */
fun Agent<*, *>.observe(handler: (PipelineEvent) -> Unit) {
    val agentName = this.name

    val priorSkill = this.skillChosenListener
    onSkillChosen { name ->
        priorSkill?.invoke(name)
        handler(PipelineEvent.SkillChosen(agentName, Instant.now(), name))
    }

    val priorTool = this.toolUseListener
    onToolUse { name, args, result ->
        priorTool?.invoke(name, args, result)
        val toolDef = toolMap[name]
        handler(
            PipelineEvent.ToolCalled(
                agentName = agentName,
                timestamp = Instant.now(),
                toolName = name,
                arguments = args,
                result = result,
                toolPolicyRisk = toolDef?.risk ?: ToolRisk.UNKNOWN,
                usedDeclaredCapability = toolDef?.policy?.declaresAnyCapability == true,
            ),
        )
    }

    val priorDenied = this.toolDeniedListener
    onToolDenied { name, args, reason ->
        priorDenied?.invoke(name, args, reason)
        val toolDef = toolMap[name]
        handler(
            PipelineEvent.ToolDenied(
                agentName = agentName,
                timestamp = Instant.now(),
                toolName = name,
                arguments = args,
                reason = reason,
                toolPolicyRisk = toolDef?.risk ?: ToolRisk.UNKNOWN,
                usedDeclaredCapability = toolDef?.policy?.declaresAnyCapability == true,
            ),
        )
    }

    val priorKnowledge = this.knowledgeUsedListener
    onKnowledgeUsed { name, content ->
        priorKnowledge?.invoke(name, content)
        handler(PipelineEvent.KnowledgeLoaded(agentName, Instant.now(), name, content.length))
    }

    val priorError = this.errorListener
    onError { error ->
        priorError?.invoke(error)
        handler(PipelineEvent.ErrorOccurred(agentName, Instant.now(), error))
    }

    val priorBudget = this.budgetThresholdListener
    onBudgetThreshold(budgetThreshold) { reason, usedPercent ->
        priorBudget?.invoke(reason, usedPercent)
        handler(PipelineEvent.BudgetThreshold(agentName, Instant.now(), reason, usedPercent))
    }

    val priorHallucinated = this.toolHallucinatedListener
    onToolHallucinated { name, args, allowed ->
        priorHallucinated?.invoke(name, args, allowed)
        handler(
            PipelineEvent.ToolHallucinated(
                agentName = agentName,
                timestamp = Instant.now(),
                requestedName = name,
                arguments = args,
                allowedTools = allowed,
            ),
        )
    }

    val priorCompressed = this.listeners.historyCompressedListener
    onHistoryCompressed { result ->
        priorCompressed?.invoke(result)
        handler(
            PipelineEvent.HistoryCompressed(
                agentName = agentName,
                timestamp = Instant.now(),
                replacedCount = result.replacedCount,
                preservedCount = result.preservedCount,
                digestChars = result.digest.length,
            ),
        )
    }

    val priorApprovalRequested = this.approvalRequestedListener
    onApprovalRequested { title, hasBody, timeoutMs ->
        priorApprovalRequested?.invoke(title, hasBody, timeoutMs)
        handler(
            PipelineEvent.ApprovalRequested(
                agentName = agentName,
                timestamp = Instant.now(),
                title = title,
                hasBody = hasBody,
                timeoutMs = timeoutMs,
            ),
        )
    }

    val priorApprovalDecided = this.approvalDecidedListener
    onApprovalDecided { decision, hasPayload ->
        priorApprovalDecided?.invoke(decision, hasPayload)
        handler(
            PipelineEvent.ApprovalDecided(
                agentName = agentName,
                timestamp = Instant.now(),
                decision = decision,
                hasPayload = hasPayload,
            ),
        )
    }
}
