---
description: Source-file knowledge for agents_engine/mcp/McpServer.kt — exposes an Agent as an MCP server over HTTP (JDK HttpServer at POST /mcp) and owns the shared JSON-RPC dispatcher reused by McpStdioServer. McpServer.from(agent) { port, expose(...) }. Non-agentic skills only (implementedBy { }); IN must be String or @Generable; output rendered as text block via toString(). Prompts/resources mirror MCP wire shape. The InternalsAgent runs on this. Call when the IDE LLM needs to reason about hosting an MCP server.
---

# `agents_engine/mcp/McpServer.kt` — expose an agent over MCP

Turns an `Agent` into an MCP server. `from(agent) { ... }` registers selected skills as MCP tools (and optionally prompts/resources) and starts an HTTP server on a configurable port. The same instance also owns the internal JSON-RPC dispatcher that `McpStdioServer` calls for line-delimited stdio.

## Quick usage

```kotlin
val server = McpServer.from(coder) {
    port = 8080         // 0 = OS-assigned
    expose("write-code", "review-code")
}.start()
println("MCP server at ${server.url}")
```

The InternalsAgent runs on this same server class (see `runtime/internals/Main.kt`).

## Scope

- **HTTP transport** — uses the JDK `com.sun.net.httpserver.HttpServer`.
- **Shared dispatch** — `dispatchJsonRpc(...)` returns one response envelope or `null` for notifications, letting `McpStdioServer` share the tool/prompt/resource behavior without duplicating handlers.
- **Non-agentic skills only** — skills declared via `implementedBy { }`. Agentic skills require server-side LLM access, which is out of scope here.
- **Skill `IN` constraints** — must be `String` OR a `@Generable` class. Other types rejected at `start()` with a descriptive error.
- **Skill output rendering** — single text content block (`toString()`).

## Tool registration

```kotlin
McpServer.from(agent) {
    expose("toolA")                       // single skill
    expose("toolA", "toolB", "toolC")     // multiple
    expose(agent.skills.keys)             // every skill
}
```

Each exposed skill becomes a `tools/list` entry with:
- `name` = skill name
- `description` = skill description
- `inputSchema` = JSON Schema derived from the skill's `inType` via `agents_engine.generation.jsonSchema`

## Prompt registration (#1796)

```kotlin
McpServer.from(agent) {
    prompt("greet") {
        description = "Say hi to the user"
        argument("name", "User's first name")
        render { args -> "Say hello to ${args["name"]}." }
    }
}
```

`RegisteredPrompt` is the internal data class carrying the wire shape:

```kotlin
internal data class RegisteredPrompt(
    val name: String,
    val description: String,
    val arguments: List<McpPromptArgument>,
    val render: (Map<String, Any?>) -> String,
)
```

Surfaces via `prompts/list` and `prompts/get` per the MCP spec.

## Resource registration

Similarly for resources and resource templates. The server holds a registered list and serves them via the corresponding `resources/*` RPCs.

## HTTP path

Serves at `POST /mcp` by default. Each request body is one JSON-RPC envelope; response is JSON or SSE depending on the operation.

Malformed JSON and requests without `method` preserve the historical HTTP behavior: `400` with an empty JSON body. Notifications return `202` with no response body. Request/response JSON-RPC methods are delegated to the shared dispatcher.

## Related files

- `Agent.kt` — the source of skills.
- `Skill.kt` — the unit registered as a tool.
- `McpRunner.kt` — the CLI wrapper around this.
- `McpStdioServer.kt` — stdio hosting over the same dispatcher.
- `McpClient.kt` — the inverse — consuming MCP servers from agents.
- `generation/jsonSchema.kt` — derives `inputSchema`.
- `runtime/internals/InternalsAgent.kt` — the framework's most prolific user.
