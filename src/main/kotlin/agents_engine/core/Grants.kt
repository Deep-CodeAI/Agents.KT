package agents_engine.core

/**
 * #4545 (PRD §9.2) — **capability grants**: the agent-level tool permission surface. Permissions are
 * *tool grants*, not magic strings — `grants { allow(writeFile); confirm(deploy) }` references actual
 * [Tool] instances. Two tiers:
 *
 * - **`allow(...)`** — granted and freely callable.
 * - **`confirm(...)`** — granted, but every call must be authorized by the **granting agent** (a
 *   parent/delegator), *not* a human user (that is [HumanDecision] / `humanApproval`). The authority
 *   is the [GrantConfirmer] supplied via [GrantsBuilder.confirmWith]; absent ⇒ **fail-closed** (the
 *   call is denied until the granting agent's authority is wired in — e.g. by a future structure DSL
 *   parent, or by the host).
 *
 * Grants are **opt-in**: an agent with no `grants { }` block is unaffected. When present, the build
 * validates that every tool the agent's skills use is granted (see `Agent`'s `build()`); the per-skill
 * allowlist already keeps ungranted tools invisible to the model, so the only *runtime* gate grants
 * adds is the `confirm(...)` authorization (enforced in `decideBeforeToolCall`). Built via
 * [GrantsBuilder] / the `grants { }` function.
 */
data class Grants(
    val allowed: Set<String>,
    val confirmRequired: Set<String>,
    val confirmer: GrantConfirmer? = null,
) {
    /** Every granted tool name — `allow(...)` ∪ `confirm(...)`. */
    val grantedNames: Set<String> get() = allowed + confirmRequired

    /**
     * The granting agent's verdict for a `confirm`-gated [toolName] call. `true` only when [toolName]
     * is confirm-gated AND a [confirmer] is wired AND it authorizes — so a missing confirmer is
     * fail-closed. Tools that are merely `allow`-ed (or not confirm-gated) are never blocked here.
     */
    fun isConfirmAuthorized(toolName: String, args: Map<String, Any?>): Boolean =
        toolName !in confirmRequired || confirmer?.authorize(toolName, args) == true
}
