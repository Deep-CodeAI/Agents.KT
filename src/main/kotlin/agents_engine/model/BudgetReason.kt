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
}
