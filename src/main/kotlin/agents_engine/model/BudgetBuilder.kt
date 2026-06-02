package agents_engine.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class BudgetBuilder {
    var maxTurns: Int = 8
    var maxToolCalls: Int = 32
    var maxDuration: Duration = 5.minutes
    var perToolTimeout: Duration? = null
    var maxTokens: Int? = null
    var maxConsecutiveSameTool: Int? = null
    var maxToolArgsBytes: Long? = null
    var maxAgentDepth: Int = DEFAULT_MAX_AGENT_DEPTH

    internal fun build() = BudgetConfig(
        maxTurns = maxTurns,
        maxToolCalls = maxToolCalls,
        maxDuration = maxDuration,
        perToolTimeout = perToolTimeout,
        maxTokens = maxTokens,
        maxConsecutiveSameTool = maxConsecutiveSameTool,
        maxToolArgsBytes = maxToolArgsBytes,
        maxAgentDepth = maxAgentDepth,
    )
}
