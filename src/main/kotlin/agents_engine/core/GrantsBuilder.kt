package agents_engine.core

/**
 * #4545 — builder for the `grants { }` block. `allow(...)` tools are freely callable; `confirm(...)`
 * tools require the granting agent's authorization ([confirmWith]). See [Grants].
 */
class GrantsBuilder {
    private val allowed = LinkedHashSet<String>()
    private val confirmRequired = LinkedHashSet<String>()
    private var confirmer: GrantConfirmer? = null

    /** Grant these tools — freely callable by the agent. */
    fun allow(vararg tools: Tool<*, *>) {
        tools.forEach { allowed += it.name }
    }

    /** Grant these tools, but require the granting agent's authorization on every call. */
    fun confirm(vararg tools: Tool<*, *>) {
        tools.forEach { confirmRequired += it.name }
    }

    /** Supply the granting agent's authorization authority for `confirm(...)` tools. */
    fun confirmWith(confirmer: GrantConfirmer) {
        this.confirmer = confirmer
    }

    internal fun build(): Grants {
        val overlap = allowed intersect confirmRequired
        require(overlap.isEmpty()) {
            "grants { }: a tool cannot be both allow(...) and confirm(...): $overlap"
        }
        return Grants(allowed.toSet(), confirmRequired.toSet(), confirmer)
    }
}

/** Standalone `grants { }` constructor (mirrors `toolPolicy { }`). */
fun grants(block: GrantsBuilder.() -> Unit): Grants = GrantsBuilder().apply(block).build()
