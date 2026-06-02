package agents_engine.mcp

/**
 * `agents_engine/mcp/McpServerInfo.kt` — immutable pure-data snapshot of an MCP server's full
 * surface (#1734): identity + protocol version + capabilities + tools + resources + resource
 * templates + prompts. Populated by `McpClient` after the initialize handshake + listings;
 * constructible directly in tests, no transport stub needed. Consumers read off this shape
 * regardless of which fields the live client has filled in.
 *
 * The component types ([McpCapabilities], [McpToolInfo], [McpResourceInfo], [McpResourceTemplateInfo],
 * [McpPromptInfo], and their annotation/argument companions) live in sibling files in this package
 * (one type per file, #3199). See `src/main/resources/internals-agent/mcp/McpServerInfo.md`
 * (#1837 / #1885).
 *
 * Forward-looking: this covers every field the MCP spec lets a server expose. `McpClient` today
 * populates only identity + tools (what it already fetches); resources, resource templates, prompts,
 * and the wider capability matrix land in follow-up issues.
 */
data class McpServerInfo(
    /** Server-reported name (from `initialize.result.serverInfo.name`). */
    val name: String,
    /** Optional human-readable title (MCP spec extension over name). */
    val title: String? = null,
    /** Server-reported version. */
    val version: String,
    /** Negotiated protocol version. */
    val protocolVersion: String,
    /** Server-provided usage hints. Some servers send a system-prompt-style preamble here. */
    val instructions: String? = null,

    /** Capability matrix — what the server says it can do. */
    val capabilities: McpCapabilities,

    /**
     * Tools exposed via `tools/list`. Null when the server's capability
     * matrix declares no `tools` support; empty when supported but empty.
     */
    val tools: List<McpToolInfo>? = null,
    /**
     * Resources exposed via `resources/list`. Null when no `resources`
     * capability; empty when supported but empty.
     */
    val resources: List<McpResourceInfo>? = null,
    /**
     * RFC 6570 resource templates via `resources/templates/list`. Same
     * presence semantics as [resources].
     */
    val resourceTemplates: List<McpResourceTemplateInfo>? = null,
    /**
     * Prompts via `prompts/list`. Null when no `prompts` capability.
     */
    val prompts: List<McpPromptInfo>? = null,
)
