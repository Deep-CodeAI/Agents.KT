package agents_engine.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * `agents_engine/model/BudgetConfig.kt` — six budget caps for an agentic
 * invocation, the matching builder, and the [BudgetReason] enum +
 * [BudgetExceededException] surfaced when a cap fires. See
 * `src/main/resources/internals-agent/model/BudgetConfig.md` for the
 * adjunct surfaced to IDE-side LLM tools via `agents-kt-internals`
 * (#1837 / #1845).
 */

/**
 * Budget caps for an agentic invocation. All defaults are production-friendly:
 * tight enough to bound cost / wall-time runaway, generous enough that
 * well-designed loops are not artificially constrained.
 *
 * Override individual fields via the `budget { }` DSL when an agent
 * legitimately needs more headroom.
 *
 * @property maxTurns hard cap on agentic loop iterations. Default 8 — most
 *   well-designed loops complete in 3–6.
 * @property maxToolCalls hard cap on total tool invocations across the loop.
 *   Default 32. Catches a single turn that emits many tool calls.
 * @property maxDuration wall-clock cap from agentic invocation start.
 *   Default 5 minutes.
 * @property perToolTimeout per-tool wall-clock cap. Null = no per-tool cap.
 * @property maxTokens hard cap on cumulative LLM tokens (prompt + completion)
 *   across all turns of the invocation. Null = no token cap. Tokens are only
 *   accumulated when the provider reports usage on the response (#963); turns
 *   with null `tokenUsage` count zero toward the cap.
 * @property maxConsecutiveSameTool hard cap on how many times the same tool
 *   can be invoked in immediate succession without any other tool call between.
 *   Null = no cap (default). Catches the common pathology where an LLM gets
 *   confused by a tool's error and retries the same broken call until
 *   `maxToolCalls` runs out. Counter resets whenever a different tool is
 *   called. (#969)
 */
data class BudgetConfig(
    val maxTurns: Int = 8,
    val maxToolCalls: Int = 32,
    val maxDuration: Duration = 5.minutes,
    val perToolTimeout: Duration? = null,
    val maxTokens: Int? = null,
    val maxConsecutiveSameTool: Int? = null,
)

class BudgetBuilder {
    var maxTurns: Int = 8
    var maxToolCalls: Int = 32
    var maxDuration: Duration = 5.minutes
    var perToolTimeout: Duration? = null
    var maxTokens: Int? = null
    var maxConsecutiveSameTool: Int? = null

    internal fun build() = BudgetConfig(
        maxTurns = maxTurns,
        maxToolCalls = maxToolCalls,
        maxDuration = maxDuration,
        perToolTimeout = perToolTimeout,
        maxTokens = maxTokens,
        maxConsecutiveSameTool = maxConsecutiveSameTool,
    )
}

enum class BudgetReason {
    TURNS,
    TOOL_CALLS,
    DURATION,
    PER_TOOL_TIMEOUT,
    TOKENS,
    CONSECUTIVE_TOOL,
}

/**
 * Returned by [agents_engine.core.Agent.onBudgetExceeded] when a budget cap is
 * about to throw (#2412). The handler can let it stop, or raise the limit and
 * continue — e.g. "ActorsAgent hit 32 tool calls but we need to keep going."
 *
 * #2412 wired this for [BudgetReason.TOOL_CALLS] only. #2750 broadened
 * coverage to [BudgetReason.TURNS], [BudgetReason.DURATION],
 * [BudgetReason.TOKENS], and [BudgetReason.CONSECUTIVE_TOOL]. The handler now
 * fires consistently at every cumulative-cap throw site. [BudgetReason
 * .PER_TOOL_TIMEOUT] remains unconditionally throwing because extending
 * mid-tool needs interrupt semantics (separate ticket).
 *
 * Units for [Extend.newLimit] by reason:
 * - [BudgetReason.TOOL_CALLS] / [BudgetReason.TURNS] / [BudgetReason.TOKENS]
 *   / [BudgetReason.CONSECUTIVE_TOOL] → integer count
 * - [BudgetReason.DURATION] → milliseconds
 */
sealed interface BudgetDecision {
    /** Throw [BudgetExceededException] — the default when no handler is registered. */
    object Stop : BudgetDecision

    /**
     * Raise the limit for the current [BudgetReason] to [newLimit] and continue.
     * Ignored (falls back to [Stop]) unless [newLimit] exceeds the current limit.
     */
    data class Extend(val newLimit: Int) : BudgetDecision

    /**
     * #2749 — pause the agentic loop at the current turn boundary. The runtime
     * captures a [agents_engine.core.SessionSnapshot] of the in-flight state,
     * delivers it to the caller via the registered `onTurnCheckpoint` hook,
     * and throws a recoverable [BudgetCheckpointException] that carries the
     * same snapshot on its own field.
     *
     * The caller (typically an HTTP handler / chat-app dialog controller)
     * catches the exception, surfaces a "raise the cap and continue?" prompt
     * to the human, and on acceptance resumes via
     * `agent.invokeSuspendResuming(input, resumeFrom = snapshot)` with the
     * budget config updated to a larger cap. The conversation history is
     * preserved end-to-end — no replay tax.
     *
     * Falls back to [Stop] semantics (throws [BudgetExceededException])
     * when no `onTurnCheckpoint` is registered on the invocation —
     * Checkpoint without a place to land the snapshot is no different from
     * Stop, and silently swallowing the budget breach would be worse.
     */
    object Checkpoint : BudgetDecision
}

open class BudgetExceededException(
    message: String,
    val reason: BudgetReason,
) : RuntimeException(message)

/**
 * #2749 — recoverable budget breach carrying the captured
 * [agents_engine.core.SessionSnapshot]. Subclass of [BudgetExceededException]
 * so existing `catch (BudgetExceededException)` blocks still fire; consumers
 * that want the snapshot path use `catch (BudgetCheckpointException)` or
 * pattern-match on the type.
 *
 * Thrown from the agentic loop when an `onBudgetExceeded` handler returns
 * [BudgetDecision.Checkpoint] AND an `onTurnCheckpoint` is registered on
 * the current invocation. Without the hook, the runtime falls back to
 * regular [BudgetExceededException] (Stop semantics).
 *
 * @property snapshot the captured loop state at the turn boundary
 *   immediately before the would-be cap breach. Resume with
 *   `agent.invokeSuspendResuming(input, resumeFrom = snapshot)`.
 * @property currentLimit the cap that the breach would have hit (passed
 *   verbatim from the breach site so the caller can compute a sensible
 *   raise — `newLimit = currentLimit * 2` or similar).
 */
class BudgetCheckpointException(
    val snapshot: agents_engine.core.SessionSnapshot,
    reason: BudgetReason,
    val currentLimit: Int,
) : BudgetExceededException(
    "Agent run checkpointed at $reason cap ($currentLimit). " +
        "Resume via invokeSuspendResuming(input, resumeFrom = exception.snapshot).",
    reason,
)
