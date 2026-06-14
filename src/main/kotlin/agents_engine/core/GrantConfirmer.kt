package agents_engine.core

/**
 * #4545 — authorization authority for `confirm(...)`-gated tools: the **granting agent's** decision,
 * not a human user's (that is [HumanDecision] / `humanApproval`). Returns `true` to let the call
 * proceed. Wired via [GrantsBuilder.confirmWith]; in a delegation topology the parent agent supplies
 * it for its child. See [Grants].
 */
fun interface GrantConfirmer {
    fun authorize(toolName: String, args: Map<String, Any?>): Boolean
}
