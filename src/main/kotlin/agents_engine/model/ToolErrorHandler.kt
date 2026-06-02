package agents_engine.model

/**
 * Built by [OnErrorBuilder.build]; holds the three repair slots the agentic loop consults when a
 * tool call's args fail to parse, its result fails to deserialize, or the executor throws.
 */
class ToolErrorHandler(
    private val invalidArgsHandler: ((String, String) -> RepairResult?)?,
    private val deserializationErrorHandler: ((String, String) -> RepairResult?)?,
    private val executionErrorHandler: ((Throwable) -> RepairResult?)?,
) {
    fun handleInvalidArgs(rawArgs: String, parseError: String): RepairResult? =
        invalidArgsHandler?.invoke(rawArgs, parseError)

    fun handleDeserializationError(rawValue: String, error: String): RepairResult? =
        deserializationErrorHandler?.invoke(rawValue, error)

    fun handleExecutionError(cause: Throwable): RepairResult? =
        executionErrorHandler?.invoke(cause)
}
