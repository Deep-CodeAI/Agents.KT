package agents_engine.model

/**
 * #4490 — per-invocation constraint state: dispatch counts and the set of
 * tools that have completed. One instance per `executeAgentic` run, so
 * counts never leak across invocations of the same (frozen, shared)
 * agent. Not thread-safe by design — a single agentic loop is
 * single-flight.
 */
internal class ToolConstraintTracker {
    private val dispatched = mutableMapOf<String, Int>()
    private val completed = mutableSetOf<String>()

    /** Reason this call must be denied, or null when the constraints allow it. */
    fun violationFor(tool: ToolDef): String? {
        val constraints = tool.constraints ?: return null
        if (constraints.forbidden) {
            return "tool '${tool.name}' is forbidden by its constraints"
        }
        constraints.maxInvocations?.let { cap ->
            if ((dispatched[tool.name] ?: 0) >= cap) {
                return "tool '${tool.name}' exceeded its maxInvocations constraint ($cap per invocation)"
            }
        }
        val missing = constraints.onlyAfter.filter { it !in completed }
        if (missing.isNotEmpty()) {
            return "tool '${tool.name}' may only run after ${missing.joinToString()} " +
                "(constraint onlyAfter; not yet completed this invocation)"
        }
        return null
    }

    /** Record a non-denied dispatch (counts toward maxInvocations). */
    fun recordDispatch(toolName: String) {
        dispatched[toolName] = (dispatched[toolName] ?: 0) + 1
    }

    /** Record successful completion (satisfies other tools' onlyAfter). */
    fun recordCompletion(toolName: String) {
        completed += toolName
    }
}
