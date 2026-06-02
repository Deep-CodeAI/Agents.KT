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
 * @property maxToolArgsBytes hard cap on the byte size of a single tool call's
 *   arguments, checked before the executor runs. Null = no cap (default).
 *   Resource-exhaustion guard (#2888, epic #2882): the model — often via
 *   prompt-injected input — can emit a tool call with enormous arguments. This
 *   is an **unconditional** cap (like `perToolTimeout`), not extendable via
 *   `onBudgetExceeded`. Size is the provider wire form (`ToolCall.rawArguments`)
 *   when present, else the serialized argument map.
 */
data class BudgetConfig(
    val maxTurns: Int = 8,
    val maxToolCalls: Int = 32,
    val maxDuration: Duration = 5.minutes,
    val perToolTimeout: Duration? = null,
    val maxTokens: Int? = null,
    val maxConsecutiveSameTool: Int? = null,
    val maxToolArgsBytes: Long? = null,
) {
    /**
     * #2805 — render a short, deterministic "what differs from defaults"
     * string for `Agent.describe()`. The previous implementation reflected
     * over `BudgetConfig::class.members` via `kotlin-reflect`, which
     * silently broke the module-wide reflect-optional contract (#1718)
     * — agents in a consumer that didn't pull `kotlin-reflect` would
     * blow up on `describe()`.
     *
     * Hand-listed properties here so adding a new budget cap is a
     * compile-time reminder to also surface it in the describe string;
     * no auto-discovery is worth the reflect dependency.
     */
    fun describeOverrides(): String {
        val d = DEFAULTS
        val overrides = buildList {
            if (maxTurns != d.maxTurns) add("maxTurns=$maxTurns")
            if (maxToolCalls != d.maxToolCalls) add("maxToolCalls=$maxToolCalls")
            if (maxDuration != d.maxDuration) add("maxDuration=$maxDuration")
            if (perToolTimeout != d.perToolTimeout) add("perToolTimeout=$perToolTimeout")
            if (maxTokens != d.maxTokens) add("maxTokens=$maxTokens")
            if (maxConsecutiveSameTool != d.maxConsecutiveSameTool) add("maxConsecutiveSameTool=$maxConsecutiveSameTool")
            if (maxToolArgsBytes != d.maxToolArgsBytes) add("maxToolArgsBytes=$maxToolArgsBytes")
        }
        return if (overrides.isEmpty()) "(defaults)" else overrides.joinToString(", ")
    }

    companion object {
        private val DEFAULTS = BudgetConfig()
    }
}
