package agents_engine.mcp

/**
 * Server-provided hints about a tool's behavior. The MCP spec is explicit that these are advisory —
 * clients must NOT rely on them for safety decisions; an LLM treating `destructiveHint = false` as
 * proof of safety is a security bug.
 */
data class McpToolAnnotations(
    val title: String? = null,
    /** Tool doesn't modify its environment. */
    val readOnlyHint: Boolean? = null,
    /** Tool may perform destructive updates. */
    val destructiveHint: Boolean? = null,
    /** Same args yield same result (no side effects worth re-checking). */
    val idempotentHint: Boolean? = null,
    /** Tool's effects extend beyond the local environment (e.g., calls external APIs). */
    val openWorldHint: Boolean? = null,
)
