package agents_engine.core

import java.util.UUID

/**
 * Runtime correlation fields carried by audit/streaming events.
 *
 * **Technical correlation** — [requestId] / [sessionId] / [manifestHash]
 * identify a single agent invocation, a resume-keyed session boundary,
 * and the capability-set hash that was authoritative at invocation time.
 *
 * **Business correlation** — [attribution] (added in #2720) carries
 * deployer-defined identifiers (who, where, what) that observability
 * bridges thread into traces. Empty by default; non-null consumers
 * (Langfuse user-id filter, LangSmith run attribution, multi-tenant
 * audit logs) set it once at session/invoke entry via
 * `withAgentRuntimeContext(currentOrNew().copy(attribution = ...))` and
 * every downstream event reads it off the same `runtimeContext`.
 */
data class AgentRuntimeContext(
    val requestId: String = UUID.randomUUID().toString(),
    val sessionId: String? = null,
    val manifestHash: String? = null,
    /**
     * #2720 — free-form business attribution. Empty by default; no
     * behavior change unless a caller sets it. Three canonical keys
     * ([AttributionKeys.USER_ID] / [AttributionKeys.PROJECT_ID] /
     * [AttributionKeys.DIALOG_ID]) have typed accessors below; arbitrary
     * additional keys (`tenantId`, `customerId`, `keyOwner`, …) are
     * permitted — bridges read whatever they need by key.
     */
    val attribution: Map<String, String> = emptyMap(),
) {
    /**
     * Canonical accessor for the invoking user / keyOwner. Returns null
     * when [attribution] does not carry an entry under
     * [AttributionKeys.USER_ID].
     */
    val userId: String? get() = attribution[AttributionKeys.USER_ID]

    /**
     * Canonical accessor for the project the run belongs to. Returns
     * null when [attribution] does not carry an entry under
     * [AttributionKeys.PROJECT_ID].
     */
    val projectId: String? get() = attribution[AttributionKeys.PROJECT_ID]

    /**
     * Canonical accessor for the deployer-defined dialog id —
     * distinct from [sessionId], which is the framework's resume-keyed
     * session boundary. Returns null when [attribution] does not carry
     * an entry under [AttributionKeys.DIALOG_ID].
     */
    val dialogId: String? get() = attribution[AttributionKeys.DIALOG_ID]

    companion object {
        fun currentOrNew(): AgentRuntimeContext =
            RuntimeContextThreadLocal.current() ?: AgentRuntimeContext()

        internal fun current(): AgentRuntimeContext? = RuntimeContextThreadLocal.current()
    }
}

/**
 * #2720 — canonical key constants for the three first-class accessors
 * on [AgentRuntimeContext.attribution]. Bridges and deployer code should
 * use these constants rather than literal strings so a future rename or
 * extension surfaces as a compile error.
 */
object AttributionKeys {
    const val USER_ID = "userId"
    const val PROJECT_ID = "projectId"
    const val DIALOG_ID = "dialogId"
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
