package agents_engine.runtime.events

import agents_engine.core.AgentRuntimeContext
import agents_engine.model.TokenUsage

/**
 * `agents_engine/runtime/events/AgentEvent.kt` — the typed sealed event
 * union surfaced via `Agent.session(input).events` (#1736). Variants:
 * [SkillStarted] / [SkillCompleted] / [Completed] / [Failed] plus
 * [ModelTurnStarted] / [ModelTurnCompleted] and [Token] /
 * [ToolCallStarted] / [ToolCallArgumentsDelta] / [ToolCallFinished].
 * Every event carries [agentId] for provenance through composition operators.
 * Only [Completed] carries the typed `OUT`; others are
 * `AgentEvent<Nothing>`. See
 * `src/main/resources/internals-agent/runtime/events/AgentEvent.md`
 * (#1837 / #1892).
 */

/**
 * #1736 — typed event emitted from `Agent.session(input).events` while the
 * agentic loop runs. See [the v0.5.0 streaming premortem](../../../../docs/premortem-0.5.0-streaming.md)
 * for the full design rationale.
 *
 * The sealed hierarchy is complete here so consumers can write exhaustive
 * `when` matches today. Implemented-by skills emit only [SkillStarted],
 * [SkillCompleted], [Completed], and [Failed]. Agentic skills also emit
 * [ModelTurnStarted], [Token], [ToolCallStarted], [ToolCallArgumentsDelta],
 * [ModelTurnCompleted], and [ToolCallFinished] as the model/tool loop runs.
 *
 * Every event carries [agentId] — the name of the agent that produced it.
 * Composition operators (`then`, `Pipeline`, `Branch`, `wrap`, `Swarm`)
 * preserve provenance via this field so a consumer collecting from a
 * composed pipeline can still tell which agent emitted which event.
 * [requestId], [sessionId], and [manifestHash] correlate the event with the
 * runtime invocation and the static manifest that approved the agent surface.
 *
 * Only [Completed] carries the typed `OUT` payload; every other subtype
 * is `AgentEvent<Nothing>` so events flow through any `AgentSession<OUT>`
 * regardless of OUT.
 */
sealed interface AgentEvent<out OUT> {
    /** The agent that produced this event. For composed pipelines this is the inner agent's name, not the composition's. */
    val agentId: String
    val runtimeContext: AgentRuntimeContext
    val requestId: String get() = runtimeContext.requestId
    val sessionId: String? get() = runtimeContext.sessionId
    val manifestHash: String? get() = runtimeContext.manifestHash

    /**
     * A model round-trip is about to begin for one skill turn. The event
     * carries model metadata only, not prompt or message contents.
     */
    data class ModelTurnStarted(
        override val agentId: String,
        val skillName: String,
        val turnIndex: Int,
        val provider: String,
        val model: String,
        val temperature: Double,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : AgentEvent<Nothing>

    /**
     * A model round-trip completed and returned either final text or tool calls.
     * [tokensUsed] is per-turn usage, not cumulative skill usage.
     */
    data class ModelTurnCompleted(
        override val agentId: String,
        val skillName: String,
        val turnIndex: Int,
        val provider: String,
        val model: String,
        val responseType: String,
        val tokensUsed: TokenUsage?,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : AgentEvent<Nothing>

    /**
     * A chunk of LLM-streamed text from a single skill turn. Providers chunk at
     * their own granularity — [text] may be a single token or a multi-token
     * chunk; the framework passes through as-is.
     *
     * Emitted by agentic skills only.
     */
    data class Token(
        override val agentId: String,
        val skillName: String,
        val text: String,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : AgentEvent<Nothing>

    /**
     * A new tool call has begun streaming. [callId] is unique per call within a
     * session; [ToolCallArgumentsDelta] and [ToolCallFinished] for the same call
     * share this id.
     *
     * Emitted by agentic skills only.
     */
    data class ToolCallStarted(
        override val agentId: String,
        val skillName: String,
        val callId: String,
        val toolName: String,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : AgentEvent<Nothing>

    /**
     * Partial tool-call arguments JSON. Multiple deltas may arrive per call for
     * providers that stream argument JSON (Anthropic, OpenAI). Non-streaming
     * providers emit one delta with the full JSON.
     *
     * Emitted by agentic skills only.
     */
    data class ToolCallArgumentsDelta(
        override val agentId: String,
        val callId: String,
        val deltaJson: String,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : AgentEvent<Nothing>

    /**
     * Tool call complete — arguments parsed, executor invoked, result captured.
     * [isError] flags executor exceptions that `onError` swallowed (loop keeps
     * going); when [isError] is true and `onError` rethrew, the session emits
     * [Failed] instead.
     *
     * Emitted by agentic skills only.
     */
    data class ToolCallFinished(
        override val agentId: String,
        val callId: String,
        val toolName: String,
        val arguments: Map<String, Any?>,
        val result: Any?,
        val isError: Boolean,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : AgentEvent<Nothing>

    /** Agent has resolved a skill and is about to execute it (typed-tool dispatch OR an `implementedBy` lambda). */
    data class SkillStarted(
        override val agentId: String,
        val skillName: String,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : AgentEvent<Nothing>

    /**
     * Skill execution complete. [tokensUsed] reports the cumulative LLM token
     * usage for this skill turn, or null for `implementedBy` skills (no LLM)
     * and for v0.5.0 step 2 (token threading lands in step 3).
     */
    data class SkillCompleted(
        override val agentId: String,
        val skillName: String,
        val tokensUsed: TokenUsage?,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : AgentEvent<Nothing>

    /** Terminal success — carries the typed output of the agent invocation. Emitted exactly once on the happy path. */
    data class Completed<out OUT>(
        override val agentId: String,
        val output: OUT,
        val tokensUsed: TokenUsage?,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : AgentEvent<OUT>

    /**
     * Terminal failure — emitted exactly once on the error path, BEFORE the
     * exception propagates. Consumers collecting `events.toList()` see this
     * as the last element; `session.await()` rethrows [cause].
     */
    data class Failed(
        override val agentId: String,
        val cause: Throwable,
        override val runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
    ) : AgentEvent<Nothing>
}

internal fun AgentEvent<*>.withRuntimeContext(context: AgentRuntimeContext): AgentEvent<*> =
    when (this) {
        is AgentEvent.ModelTurnStarted -> copy(runtimeContext = context)
        is AgentEvent.ModelTurnCompleted -> copy(runtimeContext = context)
        is AgentEvent.Token -> copy(runtimeContext = context)
        is AgentEvent.ToolCallStarted -> copy(runtimeContext = context)
        is AgentEvent.ToolCallArgumentsDelta -> copy(runtimeContext = context)
        is AgentEvent.ToolCallFinished -> copy(runtimeContext = context)
        is AgentEvent.SkillStarted -> copy(runtimeContext = context)
        is AgentEvent.SkillCompleted -> copy(runtimeContext = context)
        is AgentEvent.Completed<*> -> copy(runtimeContext = context)
        is AgentEvent.Failed -> copy(runtimeContext = context)
    }
