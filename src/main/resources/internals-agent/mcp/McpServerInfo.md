---
description: Source-file knowledge for agents_engine/mcp/McpServerInfo.kt — immutable pure-data snapshot of an MCP server's surface (#1734). identity + protocolVersion + capabilities + tools + resources + resourceTemplates + prompts. Populated by McpClient over time as RPCs land and by McpServer.snapshotFor(principal) for server-side filtered capability views. Constructible directly in tests without a transport stub. Forward-looking — fields land here before the RPC support arrives. Call when the IDE LLM needs to reason about reading MCP server state.
---

# `agents_engine/mcp/McpServerInfo.kt` — pure-data MCP server snapshot

An immutable view of an MCP server's surface (#1734). What `McpClient` produces after handshake + listings; what `McpServer.snapshotFor(principal)` returns for per-client filtered capabilities; what tests can build directly without a transport stub.

## Shape

```kotlin
data class McpServerInfo(
    val name: String,
    val title: String? = null,
    val version: String,
    val protocolVersion: String,
    val instructions: String? = null,
    val capabilities: McpCapabilities,
    val tools: List<McpToolInfo>? = null,
    val resources: List<McpResourceInfo>? = null,
    val resourceTemplates: List<McpResourceTemplateInfo>? = null,
    val prompts: List<McpPromptInfo>? = null,
)
```

Sibling types (also in this package):
- `McpCapabilities` — capability matrix (which RPCs the server supports).
- `McpToolInfo`, `McpResourceInfo`, `McpResourceTemplateInfo`, `McpPromptInfo`, `McpPromptArgument` — per-shape wire descriptors.

## Why a pure-data snapshot

Two reasons:

1. **Test affordance** — Tests can build an `McpServerInfo` directly and feed it through `toolSkills()` / `promptSkills()` / `resourceSkills()` consumers without spinning up a transport stub.
2. **Single read shape for consumers** — Whether the client has fetched everything or only some surfaces, downstream code reads off the same data class. Fields the client hasn't yet populated are `null` / empty list — no special "is this loaded" checks needed.

## Fill-in roadmap

Today's `McpClient`:
- Always populates: `name`, `version`, `protocolVersion`, `tools`.
- Partially populates: `capabilities` (whatever the server reported), `title`, `instructions`.
- Empty in current code: `resources`, `resourceTemplates`, `prompts` — fetched only when `loadResources()` / `loadResourceTemplates()` / `loadPrompts()` are called.

Each capability is a follow-up issue's worth of RPC support. The data class is forward-looking — fields land here before the RPC support, so consumers can already read from the shape.

## `instructions`

Some MCP servers send a system-prompt-style preamble in `initialize.result.instructions`. The framework records it for agents that want to inject it into their context. Most servers leave it `null`.

## Related files

- `McpClient.kt` — populator.
- `AgentMcpDsl.kt` — consumer via `toolSkills()` / `promptSkills()` / `resourceSkills()`.
- `McpToolDescriptor` / sibling types — defined in this package (separate files).
