package agents_engine.core

import java.util.UUID

/**
 * Runtime correlation fields carried by audit/streaming events.
 */
data class AgentRuntimeContext(
    val requestId: String = UUID.randomUUID().toString(),
    val sessionId: String? = null,
    val manifestHash: String? = null,
) {
    companion object {
        fun currentOrNew(): AgentRuntimeContext =
            RuntimeContextThreadLocal.current() ?: AgentRuntimeContext()

        internal fun current(): AgentRuntimeContext? = RuntimeContextThreadLocal.current()
    }
}

private object RuntimeContextThreadLocal {
    private val current = ThreadLocal<AgentRuntimeContext?>()

    fun current(): AgentRuntimeContext? = current.get()

    fun set(value: AgentRuntimeContext?) {
        current.set(value)
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
