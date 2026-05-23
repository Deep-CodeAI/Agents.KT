---
description: Source-file knowledge for agents_engine/core/Tool.kt — provider-neutral Tool<IN,OUT> contract shared by local typed tool handles and McpTool handles. Carries name, description, inputType/outputType KClass metadata, ToolRisk, optional ToolPolicy declaration, and suspend call(input). Call when the IDE LLM needs to reason about tool boundary objects for grants, manifests, audit, or MCP/local parity.
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
- `policy` is the declarative #1915 sandbox policy (`risk`, filesystem, network, environment). It is manifest/audit metadata in 0.6.0, not enforcement.
- `call(input)` invokes the concrete tool using its native adapter.

`ToolRisk` enum entries are uppercase (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`, `UNKNOWN`) with Kotlin-friendly manifest aliases (`ToolRisk.Medium`, etc.) for the policy DSL examples.

## Related files

- `model/ToolDef.kt` — local DSL `Tool<Args, Result>` handle implements this interface.
- `core/ToolPolicy.kt` — policy data classes/builders and manifest map/JSON/YAML helpers.
- `mcp/McpTool.kt` — MCP-side implementation backed by `McpClient.call`.
- `mcp/McpClient.kt` — `tools()` factory returns MCP tool handles alongside existing `toolSkills()`.
