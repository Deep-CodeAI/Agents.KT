---
description: Source-file knowledge for agents_engine/core/Tool.kt — provider-neutral Tool<IN,OUT> contract shared by local typed tool handles and McpTool handles. Carries name, description, inputType/outputType KClass metadata, risk, optional future ToolPolicy hook, and suspend call(input). Call when the IDE LLM needs to reason about tool boundary objects for grants, manifests, audit, or MCP/local parity.
---

# `agents_engine/core/Tool.kt` — provider-neutral tool contract

`Tool<IN, OUT>` is the boundary object shared by local DSL tools and MCP tools (#1948). It gives later permission-manifest, grants, audit, and policy code one common shape instead of parallel local/MCP concepts.

## Contract

```kotlin
interface Tool<IN, OUT> {
    val name: String
    val description: String
    val inputType: KClass<*>
    val outputType: KClass<*>
    val risk: ToolRisk
    val policy: ToolPolicy?

    suspend fun call(input: IN): OUT
}
```

- `name` / `description` are the display surface used by agents and manifests.
- `inputType` / `outputType` carry best-effort runtime type metadata. Local untyped tools report `Map` / `Any`; MCP tools currently report `Map` / `String`.
- `risk` defaults to local `LOW`; MCP tools derive a coarse value from MCP annotations when present.
- `policy` is the forward-compatible hook for #1915. It is intentionally only a marker here.
- `call(input)` invokes the concrete tool using its native adapter.

## Related files

- `model/ToolDef.kt` — local DSL `Tool<Args, Result>` handle implements this interface.
- `mcp/McpTool.kt` — MCP-side implementation backed by `McpClient.call`.
- `mcp/McpClient.kt` — `tools()` factory returns MCP tool handles alongside existing `toolSkills()`.
