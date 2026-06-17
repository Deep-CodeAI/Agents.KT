package agents_engine.model

import agents_engine.core.Agent
import agents_engine.core.AgentRuntimeContext
import agents_engine.core.SessionSnapshot

/**
 * `agents_engine/model/BudgetTracker.kt` — #2791 — the mutable budget state of one
 * [executeAgentic] invocation, lifted out of the loop body so the loop reads as orchestration and
 * the counters are testable in isolation.
 *
 * Holds the running counts ([turns] / [toolCalls] / [totalTokens]), the *effective* caps (each can
 * be raised mid-run by an `onBudgetExceeded` handler via [resolveCapDecision]), the
 * consecutive-same-tool counter (#969), and the pre-cap-threshold bookkeeping (#966 — fire each
 * reason's warning at most once, unless a cap is extended and the threshold re-arms).
 *
 * It also builds the [SessionSnapshot] for all capture sites (#2791 "one snapshot builder" — budget
 * checkpoint, HITL interrupt, turn boundary), reading the loop's live message list and cumulative
 * usage through the [messagesSnapshot] / [cumulativeUsage] providers supplied at construction, so the
 * counters and the snapshot stay co-located without this class reaching into the loop's other locals.
 */
internal class BudgetTracker(
    private val agent: Agent<*, *>,
    budget: BudgetConfig,
    resumeFrom: SessionSnapshot?,
    private val runtimeContext: AgentRuntimeContext,
    private val onTurnCheckpoint: ((SessionSnapshot) -> Unit)?,
    private val messagesSnapshot: () -> List<LlmMessage>,
    private val cumulativeUsage: () -> TokenUsage?,
) {
    var turns: Int = resumeFrom?.turns ?: 0
    var toolCalls: Int = resumeFrom?.toolCalls ?: 0

    // #2749 — on resume, honor whichever TOOL_CALLS limit is HIGHER between the snapshot's saved
    // limit and the current agent's budget, so the "raise the cap and resume" UX works while still
    // defaulting to the snapshot value when the budget wasn't raised.
    var toolCallLimit: Int =
        if (resumeFrom != null) maxOf(resumeFrom.toolCallLimit, budget.maxToolCalls) else budget.maxToolCalls
    var turnLimit: Int = budget.maxTurns
    var durationLimitNanos: Long = budget.maxDuration.inWholeNanoseconds
    var tokenLimit: Int? = budget.maxTokens
    var consecutiveSameToolLimit: Int? = budget.maxConsecutiveSameTool

    var totalTokens: Int = 0
    var lastToolName: String? = null
    var consecutiveSameTool: Int = 0

    // #966 — reasons whose pre-cap warning already fired this invocation.
    private val firedThresholds = mutableSetOf<BudgetReason>()

    /**
     * #2791 — one snapshot builder for every capture site. #2755: the memory slice is per-agent (not
     * the whole bank), so a shared-workspace resume doesn't disturb other agents' slots.
     */
    fun snapshot(pendingInterruptCallId: String? = null): SessionSnapshot = SessionSnapshot(
        messages = messagesSnapshot(),
        turns = turns,
        toolCalls = toolCalls,
        toolCallLimit = toolCallLimit,
        tokensUsed = cumulativeUsage(),
        memory = agent.memoryBank?.let { b ->
            b.snapshotForAgent(agent.name)?.let { v -> mapOf(agent.name to v) }
        } ?: emptyMap(),
        requestId = runtimeContext.requestId,
        sessionId = runtimeContext.sessionId,
        manifestHash = agent.manifestHash,
        pendingInterruptCallId = pendingInterruptCallId,
    )

    /** #966 — fire the agent's threshold listener at most once per reason, when usage crosses the threshold. */
    fun maybeFireThreshold(reason: BudgetReason, usedPercent: Double) {
        val listener = agent.budgetThresholdListener ?: return
        if (reason in firedThresholds) return
        if (usedPercent < agent.budgetThreshold) return
        firedThresholds += reason
        listener(reason, usedPercent)
    }

    /**
     * #969 — track consecutive invocations of the same tool across turn boundaries (what matters is
     * "no other tool came between", not "in the same turn").
     */
    fun recordConsecutiveTool(name: String) {
        if (name == lastToolName) {
            consecutiveSameTool++
        } else {
            lastToolName = name
            consecutiveSameTool = 1
        }
    }

    /**
     * #2791 — the one Stop/Extend/Checkpoint dispatch for every budget cap. Exhaustive over the
     * sealed [BudgetDecision]: a new variant is a compile error here instead of silently falling into
     * a throw. [applyNewLimit] writes the raised ceiling back into the relevant counter;
     * [rearmThreshold] re-arms the pre-cap warning after an Extend (CONSECUTIVE_TOOL keeps its
     * pre-#2791 quirk of never re-arming).
     */
    fun resolveCapDecision(
        reason: BudgetReason,
        currentLimit: Int,
        message: String,
        rearmThreshold: Boolean = true,
        applyNewLimit: (Int) -> Unit,
    ) {
        when (val decision = agent.budgetExceededListener?.invoke(reason, currentLimit)) {
            is BudgetDecision.Extend ->
                if (decision.newLimit > currentLimit) {
                    applyNewLimit(decision.newLimit)
                    if (rearmThreshold) firedThresholds.remove(reason)
                } else {
                    throw BudgetExceededException(message, reason)
                }
            BudgetDecision.Checkpoint -> checkpointAndThrow(reason, currentLimit)
            BudgetDecision.Stop, null -> throw BudgetExceededException(message, reason)
        }
    }

    private fun checkpointAndThrow(reason: BudgetReason, currentLimit: Int): Nothing {
        if (onTurnCheckpoint == null) {
            // No place to deliver the snapshot — Stop semantics. Matches the pre-#2764 fallback.
            throw BudgetExceededException("Agent '${agent.name}' exceeded $reason cap ($currentLimit)", reason)
        }
        val snap = snapshot()
        onTurnCheckpoint.invoke(snap)
        throw BudgetCheckpointException(snapshot = snap, reason = reason, currentLimit = currentLimit)
    }
}

/**
 * #1740 / #2867 / #2791 — fold one turn's provider-reported [usage] into the running cumulative
 * [TokenUsage] for the event surface. Routes through [TokenUsage.plus] so `reasoningTokens` and any
 * future field are picked up automatically; the first non-null usage seeds the accumulator.
 */
internal fun accumulateUsage(cumulative: TokenUsage?, usage: TokenUsage): TokenUsage =
    cumulative?.plus(usage) ?: usage
