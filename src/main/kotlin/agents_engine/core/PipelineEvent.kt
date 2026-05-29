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
    /** #2720 — convenience accessor for the deployer-defined attribution map. */
    val attribution: Map<String, String> get() = runtimeContext.attribution
    /** #2720 — invoking user / keyOwner, when set on the runtime context. */
    val userId: String? get() = runtimeContext.userId
    /** #2720 — project the run belongs to, when set on the runtime context. */
    val projectId: String? get() = runtimeContext.projectId
    /** #2720 — deployer-defined dialog id (distinct from [sessionId]), when set. */
    val dialogId: String? get() = runtimeContext.dialogId

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
}
