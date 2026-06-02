package agents_engine.model

import agents_engine.core.Agent

/**
 * Receiver for the `onError { }` repair blocks ([OnErrorBuilder]). `fix(agent)` delegates the repair
 * to a sibling string→string agent; `retry(n)` requests a bounded re-attempt.
 */
class RepairScope(private val input: String) {

    fun fix(agent: Agent<String, String>, retries: Int = 1): RepairResult {
        return executeAgentFix(agent, input, retries)
    }

    fun sanitize(agent: Agent<String, String>, retries: Int = 1): RepairResult =
        fix(agent, retries)

    fun retry(maxAttempts: Int): RepairResult.Retry = RepairResult.Retry(maxAttempts)
}
