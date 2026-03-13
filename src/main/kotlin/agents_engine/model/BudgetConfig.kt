package agents_engine.model

data class BudgetConfig(
    val maxTurns: Int = Int.MAX_VALUE,
)

class BudgetBuilder {
    var maxTurns: Int = Int.MAX_VALUE

    internal fun build() = BudgetConfig(maxTurns)
}

class BudgetExceededException(message: String) : RuntimeException(message)
