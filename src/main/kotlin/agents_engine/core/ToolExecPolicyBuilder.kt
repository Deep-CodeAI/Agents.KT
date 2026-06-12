package agents_engine.core

/** #2887 — DSL builder for the `exec { }` block inside `policy { }`. */
class ToolExecPolicyBuilder internal constructor(initial: ToolExecPolicy) {
    private var policy: ToolExecPolicy = initial

    /** The executor may spawn subprocesses (use `processTool` for the sandboxed path). */
    fun allow() {
        policy = ToolExecPolicy.Allow
    }

    /** The executor explicitly never spawns subprocesses. */
    fun deny() {
        policy = ToolExecPolicy.Deny
    }

    internal fun build(): ToolExecPolicy = policy
}
