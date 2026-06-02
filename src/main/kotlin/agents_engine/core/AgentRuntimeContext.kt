package agents_engine.core

import java.util.UUID

/**
 * Runtime correlation fields carried by audit/streaming events.
 */
data class AgentRuntimeContext(
    val requestId: String = UUID.randomUUID().toString(),
    val sessionId: String? = null,
    val manifestHash: String? = null,
    /**
     * #3377 — nested agent-invocation depth. 0 at a top-level invoke; each nested invoke (a tool
     * that calls back into an agent — Swarm `absorb`, agent-as-tool) increments it via
     * [Agent.newRuntimeContext], which reads the current context. Enforced against
     * `budget.maxAgentDepth` so a self-re-entering agent fails fast instead of recursing unbounded.
     */
    val depth: Int = 0,
) {
    companion object {
        fun currentOrNew(): AgentRuntimeContext =
            RuntimeContextThreadLocal.current() ?: AgentRuntimeContext()

        internal fun current(): AgentRuntimeContext? = RuntimeContextThreadLocal.current()
    }
}

internal suspend fun <T> withAgentRuntimeContext(
    context: AgentRuntimeContext,
    block: suspend () -> T,
): T {
    val previous = RuntimeContextThreadLocal.current()
    RuntimeContextThreadLocal.set(context)
    return try {
        block()
    } finally {
        RuntimeContextThreadLocal.set(previous)
    }
}
