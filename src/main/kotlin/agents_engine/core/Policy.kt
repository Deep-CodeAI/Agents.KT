package agents_engine.core

/**
 * `agents_engine/core/Policy.kt` — declarative policy DSL (#2490).
 *
 * **Sugar over existing enforcement surfaces**, not a new check layer. A
 * `policy { }` block at agent construction declares high-level intent
 * (approval gates, redaction targets); the framework wires it into
 * existing machinery — the [interrupt] / `humanApproval { }` gate
 * (#2488 / #2489), the JSONL audit exporter's redaction pipeline, and
 * the permission manifest's policy section. No new runtime check is
 * introduced.
 *
 * ```kotlin
 * agent<String, String>("Deployer") {
 *     // ... model / tools / skills ...
 *     policy {
 *         requireHumanApprovalFor("send_email", "deploy", "refund")
 *         redact("apiKey", "password", "token")
 *     }
 * }
 * ```
 *
 * **`requireHumanApprovalFor(names)`** — before invoking each listed
 * tool, the agentic loop calls `humanApproval { title = "Approve tool
 * call: $name"; body = call.arguments }`. The caller catches
 * [AgentInterruptException], reviews, and resumes via
 * `invokeSuspendResuming(input, resumeFrom = snapshot, resumeWith =
 * <HumanDecision>)`. The runtime dispatches by decision:
 *
 * - [HumanDecision.Approved] → the original tool runs with original args.
 * - [HumanDecision.Edited] → the original tool runs with edited args
 *   (`Edited.payload` must be a `Map<String, Any?>`).
 * - [HumanDecision.Rejected] → the tool does NOT run; the model sees
 *   a "rejected by human" tool result and continues.
 * - [HumanDecision.Responded] → the tool does NOT run; the model sees
 *   `Responded.payload` (rendered via `toLlmInput`) as the tool result.
 *
 * **`redact(fields)`** — names of argument / result fields whose values
 * the JSONL audit exporter and bridges (OTel / LangSmith / Langfuse)
 * replace with `"[REDACTED]"` before writing the audit row. Field-name
 * match is case-sensitive. Body content is never written to audit
 * rows by default — `redact` covers the field names that DO leak
 * through specialised tool-call audit rows.
 *
 * **`denyToolsForRole(role, ...)`** — deferred to a follow-up ticket.
 * Needs an `AgentRoleContext` propagation mechanism that doesn't
 * exist yet.
 *
 * The policy itself is part of the permission manifest and covered by
 * the manifest hash (#2754 restore guard composes — an auditor can
 * verify the running system enforced the same policy that was
 * signed off).
 *
 * Pairs with #2487 (HITL epic).
 */
data class Policy(
    /**
     * Tool names that require a [HumanDecision] before the executor runs.
     * Each listed name must reference a tool registered on the agent
     * (validated at construction).
     */
    val approvalRequiredTools: Set<String> = emptySet(),
    /**
     * Argument / result field names to redact in audit rows.
     * Field-name match is case-sensitive.
     */
    val redactionFields: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY: Policy = Policy()
    }
}

/**
 * DSL builder for [Policy], surfaced through `agent { policy { ... } }`.
 */
class PolicyBuilder {
    private val approvalNames: MutableSet<String> = linkedSetOf()
    private val redactFields: MutableSet<String> = linkedSetOf()

    /**
     * Gate the listed tools with `humanApproval { }`. Each call to the
     * tool throws [AgentInterruptException] carrying an
     * [ApprovalRequest]; the caller resumes with a [HumanDecision].
     *
     * Names must reference tools registered on the agent — typos fail
     * fast at `agent { }` construction (same philosophy as #631 for
     * skill tool names).
     */
    fun requireHumanApprovalFor(vararg toolNames: String) {
        approvalNames += toolNames
    }

    /**
     * Redact the listed field names in audit-row argument / result
     * snapshots. Field-name match is case-sensitive.
     */
    fun redact(vararg fieldNames: String) {
        redactFields += fieldNames
    }

    internal fun build(): Policy = Policy(
        approvalRequiredTools = approvalNames.toSet(),
        redactionFields = redactFields.toSet(),
    )
}

/**
 * #2490b — replace matching field values with `"[REDACTED]"` for audit-row
 * writeout. Public so observability bridges can call it directly when
 * serialising event arguments.
 *
 * Matches case-sensitively on the top-level keys of [args]. Recurses
 * into nested `Map<String, Any?>` values so a `headers: { authorization
 * }` shape is also covered. Does NOT recurse into `List` entries —
 * per-list-element redaction is out of scope for v1; field-level
 * targeting is enough for typical secret-bearing API arg shapes.
 *
 * Returns the original map unchanged when [fields] is empty, so no
 * allocation cost on the default (empty policy) path.
 */
fun redactArguments(args: Map<String, Any?>, fields: Set<String>): Map<String, Any?> {
    if (fields.isEmpty()) return args
    val redacted = mutableMapOf<String, Any?>()
    for ((key, value) in args) {
        redacted[key] = when {
            key in fields -> "[REDACTED]"
            value is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                redactArguments(value as Map<String, Any?>, fields)
            }
            else -> value
        }
    }
    return redacted
}
