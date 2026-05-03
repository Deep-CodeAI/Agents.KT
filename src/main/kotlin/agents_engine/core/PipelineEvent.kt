package agents_engine.core

import java.time.Instant

/**
 * Typed event union emitted via [Agent.observe]. Bridges the four existing
 * agent-level hooks (`onSkillChosen`, `onToolUse`, `onKnowledgeUsed`,
 * `onError`) into a single sealed type so integrators wiring telemetry can
 * use one `when` block instead of four separate registrations. See #965.
 *
 * The event surface is intentionally a SUBSET of the full PRD §10.2 hierarchy
 * — `TextDelta`, `BudgetWarning`, `SubAgentSpawned`, `ContextCompacted`,
 * `Pipeline*`, `Inference*` events depend on infrastructure that isn't
 * shipped yet (streaming, threshold hooks, sub-agents, sessions, pipeline-
 * level event sources). Those land in follow-ups as the underlying
 * machinery arrives.
 *
 * `agentName` and `timestamp` are present on every variant so consumers can
 * sort, filter, and attribute events without inspecting the variant.
 */
sealed interface PipelineEvent {
    val agentName: String
    val timestamp: Instant

    data class SkillChosen(
        override val agentName: String,
        override val timestamp: Instant,
        val skillName: String,
    ) : PipelineEvent

    data class ToolCalled(
        override val agentName: String,
        override val timestamp: Instant,
        val toolName: String,
        val arguments: Map<String, Any?>,
        val result: Any?,
    ) : PipelineEvent

    data class KnowledgeLoaded(
        override val agentName: String,
        override val timestamp: Instant,
        val entryName: String,
        val contentLength: Int,
    ) : PipelineEvent

    data class ErrorOccurred(
        override val agentName: String,
        override val timestamp: Instant,
        val error: Throwable,
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
        handler(PipelineEvent.ToolCalled(agentName, Instant.now(), name, args, result))
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
}
