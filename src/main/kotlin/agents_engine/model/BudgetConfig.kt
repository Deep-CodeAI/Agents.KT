package agents_engine.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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
 */
data class BudgetConfig(
    val maxTurns: Int = 8,
    val maxToolCalls: Int = 32,
    val maxDuration: Duration = 5.minutes,
    val perToolTimeout: Duration? = null,
)

class BudgetBuilder {
    var maxTurns: Int = 8
    var maxToolCalls: Int = 32
    var maxDuration: Duration = 5.minutes
    var perToolTimeout: Duration? = null

    internal fun build() = BudgetConfig(
        maxTurns = maxTurns,
        maxToolCalls = maxToolCalls,
        maxDuration = maxDuration,
        perToolTimeout = perToolTimeout,
    )
}

enum class BudgetReason { TURNS, TOOL_CALLS, DURATION, PER_TOOL_TIMEOUT }

class BudgetExceededException(
    message: String,
    val reason: BudgetReason,
) : RuntimeException(message)
