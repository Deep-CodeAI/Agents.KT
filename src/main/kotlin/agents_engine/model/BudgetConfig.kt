package agents_engine.model

/**
 * Default `maxTurns = 8` — most well-designed agentic loops complete in 3–6.
 * This is a defensive cap to prevent runaway loops from burning unbounded
 * tokens / making unbounded side-effecting tool calls. Override with
 * `budget { maxTurns = N }` if your agent legitimately needs more turns.
 *
 * Richer budgets (`maxToolCalls`, `maxDuration`, `perToolTimeout`, `maxTokens`)
 * land in a separate ticket.
 */
data class BudgetConfig(
    val maxTurns: Int = 8,
)

class BudgetBuilder {
    var maxTurns: Int = 8

    internal fun build() = BudgetConfig(maxTurns)
}

class BudgetExceededException(message: String) : RuntimeException(message)
