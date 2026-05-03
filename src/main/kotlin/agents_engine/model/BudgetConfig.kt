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

class BudgetExceededException(
    message: String,
    val reason: BudgetReason,
) : RuntimeException(message)
