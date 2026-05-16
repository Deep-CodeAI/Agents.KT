---
description: Source-file knowledge for agents_engine/mcp/McpProtocolVersion.kt — one-line file holding MCP_PROTOCOL_VERSION constant (currently "2025-03-26"). Single source of truth — bump here when upgrading MCP spec revision. Used by McpClient.handshake() and McpServer identity. Call when the IDE LLM needs to know the MCP version targeted.
---

# `agents_engine/mcp/McpProtocolVersion.kt` — protocol version constant

Single-line file:

```kotlin
const val MCP_PROTOCOL_VERSION: String = "2025-03-26"
```

## Single source of truth

Every place that needs to declare or advertise the protocol version reads this constant:

- `McpClient.handshake()` — sends this as the `initialize.params.protocolVersion`.
- `McpServer` — declares this as the server-side `serverInfo.protocolVersion`.
- Tests / mock servers — use this for fixture defaults.

Bumping here is the entire upgrade. The framework supports a single protocol version at a time (no multi-version negotiation logic yet).

## Format

MCP uses date-flavored versioning. The MCP spec uses `YYYY-MM-DD` strings. The current value `"2025-03-26"` matches the MCP spec revision the framework targets.

## When to bump

When upgrading to a new MCP spec revision:
1. Update this constant.
2. Audit `McpClient` / `McpServer` for new RPC methods, capability shapes, etc.
3. Update tests / mock servers to advertise the new version.
4. Run integration tests against the upgraded servers.

## Related files

- `McpClient.kt` — uses this in `initialize`.
- `McpServer.kt` — uses this in server-side identity.
- `McpServerInfo.kt` — the `protocolVersion` field stores the negotiated version (which equals this when the client speaks first).
