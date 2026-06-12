package agents_engine.core

/**
 * #4490 (PRD §"tool constraints") — per-tool **usage** rules, the sibling
 * of [ToolPolicy]: the policy declares *what* a tool may touch, the
 * constraints declare *when and how often* it may run within one
 * invocation. Enforced in the agentic loop's dispatch path; a violating
 * call is denied through the same auditable path as a policy denial
 * (`onToolDenied` / `PipelineEvent.ToolDenied`), and the model sees the
 * denial as the tool result so it can self-correct.
 *
 * v1 set: [maxInvocations], [onlyAfter], [forbidden]. `ForceAtStep` and
 * `RequiresApproval` from the PRD sketch are deferred — approval already
 * has a first-class shape (`humanApproval` / `HumanGateRegistry`).
 */
data class ToolConstraints(
    /** Maximum dispatches of this tool per agent invocation. */
    val maxInvocations: Int? = null,
    /** Tools that must each have completed at least once before this tool may run. */
    val onlyAfter: List<String> = emptyList(),
    /**
     * The tool may never run in the agentic path — it stays visible to
     * code (`agent.toolMap`) but the model cannot dispatch it. Useful for
     * temporarily quarantining a tool without rewiring allowlists.
     */
    val forbidden: Boolean = false,
) {
    init {
        maxInvocations?.let { require(it > 0) { "maxInvocations must be positive, was $it." } }
    }

    val declaresAny: Boolean get() = maxInvocations != null || onlyAfter.isNotEmpty() || forbidden

    fun toManifestMap(): Map<String, Any?> = linkedMapOf(
        "maxInvocations" to maxInvocations,
        "onlyAfter" to onlyAfter,
        "forbidden" to forbidden,
    )
}
