package agents_engine.mcp

/**
 * `agents_engine/mcp/McpProtocolVersion.kt` — single-line file holding
 * the negotiated MCP protocol version. Single source of truth — bump
 * here when upgrading. See
 * `src/main/resources/internals-agent/mcp/McpProtocolVersion.md`
 * (#1837 / #1882).
 */

/**
 * MCP protocol version that this client speaks by default and that the mock server
 * declares unless overridden. Single source of truth — bump here when upgrading.
 */
const val MCP_PROTOCOL_VERSION: String = "2025-03-26"
