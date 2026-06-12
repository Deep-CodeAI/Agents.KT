package agents_engine.core

/** #4490 — DSL builder for the `constraints { }` block on a tool. */
class ToolConstraintsBuilder {
    /** Cap dispatches of this tool per agent invocation. */
    var maxInvocations: Int? = null

    private val onlyAfter = mutableListOf<String>()
    private var forbidden = false

    /** This tool may only run after each named tool has completed at least once. */
    fun onlyAfter(vararg toolNames: String) {
        onlyAfter += toolNames.map { nonBlank(it, "onlyAfter tool name") }
    }

    /** The model may never dispatch this tool. */
    fun forbidden() {
        forbidden = true
    }

    internal fun build(): ToolConstraints =
        ToolConstraints(maxInvocations = maxInvocations, onlyAfter = onlyAfter.toList(), forbidden = forbidden)
}
