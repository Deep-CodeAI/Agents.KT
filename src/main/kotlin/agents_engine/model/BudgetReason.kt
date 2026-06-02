package agents_engine.model

enum class BudgetReason {
    TURNS,
    TOOL_CALLS,
    DURATION,
    PER_TOOL_TIMEOUT,
    TOKENS,
    CONSECUTIVE_TOOL,

    /**
     * A single tool call's arguments exceeded `maxToolArgsBytes` (#2888). Like
     * [PER_TOOL_TIMEOUT], this is an unconditional hard cap — not extendable via
     * `onBudgetExceeded` (an oversized payload is rejected, not negotiated).
     */
    TOOL_ARGS_SIZE,

    /**
     * Nested agent-invocation depth exceeded `maxAgentDepth` (#3377). A self-re-entering agent or an
     * A→B→A cycle would otherwise recurse one full agentic loop per level. Like [PER_TOOL_TIMEOUT] /
     * [TOOL_ARGS_SIZE], an unconditional safety stop — not extendable via `onBudgetExceeded`.
     */
    AGENT_DEPTH,
}
