package agents_engine.model

open class BudgetExceededException(
    message: String,
    val reason: BudgetReason,
) : RuntimeException(message)
