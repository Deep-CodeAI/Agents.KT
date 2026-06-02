package agents_engine.model

import agents_engine.core.ToolPolicy
import agents_engine.core.toolPolicy

class ToolDefBuilder(private val name: String) {
    private var desc: String = ""
    private var exec: ((Map<String, Any?>) -> Any?)? = null
    private var handler: ToolErrorHandler? = null
    private var untrusted: Boolean = false
    private var policy: ToolPolicy? = null

    fun description(text: String) { desc = text }

    fun executor(block: (Map<String, Any?>) -> Any?) { exec = block }

    fun policy(block: agents_engine.core.ToolPolicyBuilder.() -> Unit) {
        policy = toolPolicy(block)
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
            executor = requireNotNull(exec) { "Tool \"$name\" must have an executor { } block." },
        )
        handler?.let { def.errorHandler = it }
        return def
    }
}
