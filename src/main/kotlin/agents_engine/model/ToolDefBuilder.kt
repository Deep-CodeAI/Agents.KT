package agents_engine.model

import agents_engine.core.ToolPolicy
import agents_engine.core.toolPolicy

class ToolDefBuilder(private val name: String) {
    private var desc: String = ""
    private var exec: ((Map<String, Any?>) -> Any?)? = null
    private var envExec: ((Map<String, Any?>, agents_engine.core.ToolEnvironment) -> Any?)? = null
    private var handler: ToolErrorHandler? = null
    private var untrusted: Boolean = false
    private var policy: ToolPolicy? = null
    private var constraints: agents_engine.core.ToolConstraints? = null

    fun description(text: String) { desc = text }

    @Deprecated(
        message = "Tool executors are moving to the (args, env) shape — ToolEnvironment gates " +
            "filesystem/env access by the declared policy (#2889). The single-arg form keeps " +
            "working for one minor release.",
        replaceWith = ReplaceWith("executor { args, _ -> block(args) }"),
        level = DeprecationLevel.WARNING,
    )
    fun executor(block: (Map<String, Any?>) -> Any?) { exec = block }

    /**
     * #2889 — the (args, env) executor shape. [agents_engine.core.ToolEnvironment]
     * is constructed per call from the tool's declared policy: an operation the
     * policy doesn't grant throws [agents_engine.core.ToolPolicyViolation] before
     * it happens.
     */
    fun executor(block: (args: Map<String, Any?>, env: agents_engine.core.ToolEnvironment) -> Any?) {
        envExec = block
    }

    fun policy(block: agents_engine.core.ToolPolicyBuilder.() -> Unit) {
        policy = toolPolicy(block)
    }

    /** #4490 — per-invocation usage rules: maxInvocations / onlyAfter / forbidden. */
    fun constraints(block: agents_engine.core.ToolConstraintsBuilder.() -> Unit) {
        constraints = agents_engine.core.ToolConstraintsBuilder().apply(block).build()
    }

    fun onError(block: OnErrorBuilder.() -> Unit) {
        handler = OnErrorBuilder().apply(block).build()
    }

    /**
     * Mark this tool's output as originating outside the agent's trust boundary
     * (network responses, user uploads, search results). The agentic loop will
     * wrap the result in a `ToolResultEnvelope` JSON with `trusted: false` before
     * injecting it into the LLM context, and the system prompt will warn the
     * model to treat such content as data rather than instructions. See #642.
     */
    fun untrustedOutput() { untrusted = true }

    internal fun build(): ToolDef {
        val def = ToolDef(
            name = name,
            description = desc,
            untrustedOutput = untrusted,
            risk = policy?.risk ?: agents_engine.core.ToolRisk.LOW,
            policy = policy,
            constraints = constraints,
            executor = effectiveExecutor(),
        )
        handler?.let { def.errorHandler = it }
        return def
    }

    private fun effectiveExecutor(): (Map<String, Any?>) -> Any? {
        val envBlock = envExec
        val plainBlock = exec
        return when {
            // #2889 — per-call policy-gated environment; both loop chokepoints
            // (regular + session) reach executors through this single seam.
            envBlock != null -> { args ->
                envBlock(args, agents_engine.core.JvmToolEnvironment(name, policy))
            }
            plainBlock != null -> plainBlock
            else -> error("Tool \"$name\" must have an executor { } block.")
        }
    }
}
