package agents_engine.mcp

/**
 * `agents_engine/mcp/McpServerInfo.kt` — immutable pure-data snapshot of
 * an MCP server's full surface (#1734). Identity + protocol version +
 * capabilities + tools + resources + resource templates + prompts.
 * Populated by `McpClient` after handshake + listings; constructible
 * directly in tests. Consumers read off this shape regardless of
 * which fields the live client has filled in. See
 * `src/main/resources/internals-agent/mcp/McpServerInfo.md`
 * (#1837 / #1885).
 */

// #1734 — immutable pure-data snapshot of an MCP server's full surface.
// Materialized by McpClient after the initialize handshake + listings;
// can also be constructed directly in tests, no transport stub needed.
//
// Forward-looking: this covers every field the MCP spec lets a server
// expose (identity, capabilities, tools, resources, resource templates,
// prompts). McpClient today populates only identity + tools (what it
// already fetches); resources, resource templates, prompts, and the
// capability matrix beyond what's needed for tool dispatch land in
// follow-up issues. Consumers read off the same shape regardless of
// which fields the live client has filled in.

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

data class McpCapabilities(
    /** Tool listing + invocation. Null when the server doesn't expose tools at all. */
    val tools: McpToolsCapability? = null,
    /** Resource listing + reading. */
    val resources: McpResourcesCapability? = null,
    /** Prompt listing + retrieval. */
    val prompts: McpPromptsCapability? = null,
    /** Server can emit `logging/message` notifications. */
    val logging: Boolean = false,
    /** Server supports `completion/complete` for argument completion. */
    val completions: Boolean = false,
    /** Catch-all for capabilities not yet standardized in the MCP spec. */
    val experimental: Map<String, Any?> = emptyMap(),
)

data class McpToolsCapability(
    /** Server emits `notifications/tools/list_changed` when its tool list mutates. */
    val listChanged: Boolean = false,
)

data class McpResourcesCapability(
    /** Server emits `notifications/resources/list_changed`. */
    val listChanged: Boolean = false,
    /** Server supports `resources/subscribe` for per-resource update notifications. */
    val subscribe: Boolean = false,
)

data class McpPromptsCapability(
    /** Server emits `notifications/prompts/list_changed`. */
    val listChanged: Boolean = false,
)

data class McpToolInfo(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    /** JSON Schema describing the tool's argument shape. */
    val inputSchema: Map<String, Any?>,
    /** Optional JSON Schema describing the tool's structured result shape. */
    val outputSchema: Map<String, Any?>? = null,
    val annotations: McpToolAnnotations? = null,
)

/**
 * Server-provided hints about a tool's behavior. The MCP spec is explicit
 * that these are advisory — clients must NOT rely on them for safety
 * decisions; an LLM treating `destructiveHint = false` as proof of safety
 * is a security bug.
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

data class McpResourceInfo(
    val uri: String,
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
    /** Size in bytes when known. */
    val size: Long? = null,
    val annotations: McpResourceAnnotations? = null,
)

data class McpResourceAnnotations(
    /** Intended consumer(s): `"user"`, `"assistant"`, or both. */
    val audience: List<String>? = null,
    /** 0.0 (least important) to 1.0 (most important). */
    val priority: Double? = null,
    /** ISO 8601 timestamp of the resource's last modification. */
    val lastModified: String? = null,
)

data class McpResourceTemplateInfo(
    /** RFC 6570 URI template (e.g., `file:///{path}`). */
    val uriTemplate: String,
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
)

data class McpPromptInfo(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val arguments: List<McpPromptArgument> = emptyList(),
)

data class McpPromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean = false,
)
