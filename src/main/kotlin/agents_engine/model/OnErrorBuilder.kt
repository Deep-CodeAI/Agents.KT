package agents_engine.model

import agents_engine.core.Agent

sealed interface RepairResult {
    data class Fixed(val value: String) : RepairResult
    data class Retry(val maxAttempts: Int) : RepairResult
    data class Escalated(val reason: String, val severity: Severity) : RepairResult
    data object Unrecoverable : RepairResult
}

class RepairScope(private val input: String) {

    fun fix(block: () -> String?): RepairResult? {
        val fixed = block()
        return if (fixed != null) RepairResult.Fixed(fixed) else null
    }

    fun fix(agent: Agent<String, String>, retries: Int = 1): RepairResult {
        return executeAgentFix(agent, input, retries)
    }

    fun sanitize(block: () -> String?): RepairResult? = fix(block)

    fun sanitize(agent: Agent<String, String>, retries: Int = 1): RepairResult =
        fix(agent, retries)

    fun retry(maxAttempts: Int): RepairResult.Retry = RepairResult.Retry(maxAttempts)

    @Suppress("unused")
    val Fail: RepairResult get() = RepairResult.Unrecoverable
}

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

class OnErrorBuilder {
    private var invalidArgsBlock: ((String, String) -> RepairResult?)? = null
    private var deserializationErrorBlock: ((String, String) -> RepairResult?)? = null
    private var executionErrorBlock: ((Throwable) -> RepairResult?)? = null

    fun invalidArgs(block: RepairScope.(raw: String, error: String) -> RepairResult?) {
        invalidArgsBlock = { raw, error ->
            RepairScope(raw).block(raw, error) ?: RepairResult.Unrecoverable
        }
    }

    fun deserializationError(block: RepairScope.(raw: String, error: String) -> RepairResult?) {
        deserializationErrorBlock = { raw, error ->
            RepairScope(raw).block(raw, error) ?: RepairResult.Unrecoverable
        }
    }

    fun executionError(block: RepairScope.(cause: Throwable) -> RepairResult?) {
        executionErrorBlock = { cause ->
            RepairScope("").block(cause)
        }
    }

    fun build(): ToolErrorHandler = ToolErrorHandler(
        invalidArgsHandler = invalidArgsBlock,
        deserializationErrorHandler = deserializationErrorBlock,
        executionErrorHandler = executionErrorBlock,
    )
}

internal fun executeAgentFix(
    agent: Agent<String, String>,
    input: String,
    retries: Int,
): RepairResult {
    repeat(retries) {
        try {
            val result = agent(input)
            return RepairResult.Fixed(result)
        } catch (e: EscalationException) {
            return RepairResult.Escalated(e.reason, e.severity)
        } catch (e: ToolExecutionException) {
            throw e
        } catch (_: Throwable) {
            // retry
        }
    }
    return RepairResult.Unrecoverable
}
