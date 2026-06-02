package agents_engine.model

import agents_engine.core.Agent

/**
 * `agents_engine/model/OnErrorBuilder.kt` — the `onError { }` recovery
 * DSL: three handler slots ([invalidArgs], [deserializationError],
 * [executionError]) returning a [RepairResult] (`Fixed`, `Retry`,
 * `Escalated`, `Unrecoverable`). The agentic loop consults these when a
 * tool call's args fail to parse, the result fails to deserialize, or
 * the executor throws. The `fix(agent)` helper inside [RepairScope]
 * delegates the repair to a sibling string→string agent. See
 * `src/main/resources/internals-agent/model/OnErrorBuilder.md`
 * (#1837 / #1854).
 */

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
