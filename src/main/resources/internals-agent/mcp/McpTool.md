---
description: Source-file knowledge for agents_engine/mcp/McpTool.kt — McpTool<IN,OUT> adapts an McpToolDescriptor and McpClient into the provider-neutral core Tool interface. Used by McpClient.tools() as the additive typed-tool surface beside toolSkills(). Call when the IDE LLM needs to reason about MCP tools as first-class boundary/grant/manifest objects.
---

# `agents_engine/mcp/McpTool.kt` — MCP tool handle

`McpTool<IN, OUT>` is the MCP-native implementation of `agents_engine.core.Tool<IN, OUT>` (#1948). It is additive to the skills-shaped adapter: `McpClient.toolSkills()` remains the prompt-style primary-skill surface, while `McpClient.tools()` returns tool-shaped handles for grants, manifests, policy, and audit work.

## Shape

```kotlin
class McpTool<IN, OUT> internal constructor(...) : Tool<IN, OUT> {
    override suspend fun call(input: IN): OUT =
        outputAdapter(client.call(wireName, inputAdapter(input)))
}
```

The public factory is `McpClient.tools(prefix: String? = null)`, which currently materializes every discovered MCP descriptor as `McpTool<Map<String, Any?>, String>`.

## Risk mapping

MCP annotations provide only hints, so the mapping is deliberately coarse:

- `destructiveHint == true` -> `ToolRisk.HIGH`
- `openWorldHint == true` -> `ToolRisk.MEDIUM`
- `readOnlyHint == true` -> `ToolRisk.LOW`
- missing/unknown annotations -> `ToolRisk.UNKNOWN`

`policy` is null until #1915 lands the declarative policy DSL.

## Related files

- `core/Tool.kt` — provider-neutral tool contract.
- `mcp/McpClient.kt` — owns descriptors and creates `McpTool` handles.
- `mcp/McpServerInfo.kt` — MCP wire/snapshot shapes, including `McpToolAnnotations`.
