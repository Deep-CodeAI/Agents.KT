package agents_engine.core

internal object RuntimeContextThreadLocal {
    private val current = ThreadLocal<AgentRuntimeContext?>()

    fun current(): AgentRuntimeContext? = current.get()

    fun set(value: AgentRuntimeContext?) {
        current.set(value)
    }
}
