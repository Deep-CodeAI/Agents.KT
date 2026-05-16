# `agents_engine/mcp/AgentMcpDsl.kt` — declarative MCP server registration

The `agent { mcp { server(...) } }` DSL. Connects to MCP servers at agent-construction time and registers their tools.

## Three connection shapes

```kotlin
agent<X, Y>("coder") {
    mcp {
        server("github") {
            url = "https://api.github.com/mcp"   // Streamable HTTP
            auth = McpAuth.Bearer("ghp_xxx")
        }
        server("filesystem") {
            command = listOf("npx", "@modelcontextprotocol/server-filesystem", "/src")  // stdio
        }
        server("internal") {
            host = "mcp.internal"                // TCP
            port = 9000
        }
    }
}
```

The three shapes are mutually exclusive: `url` → HTTP, `command` → stdio, `host+port` → TCP.

## Tool prefixing

Each `server(name) { }` registers its tools into `agent.toolMap` with prefix = server name. So GitHub's `create_pr` tool shows up as `github.create_pr` in the agent, `filesystem.read_file` for the filesystem server, etc. Prevents name collisions across servers.

## v0.5.0: capability shapes as Skills

`mcp.toolSkills()` / `promptSkills()` / `resourceSkills()` expose every MCP capability shape as `Skill<Map<String, Any?>, String>`:

```kotlin
agent<String, String>("dispatcher") {
    skills {
        mcp.toolSkills(server = "github")        // each tool becomes a Skill
        mcp.promptSkills(server = "claude-docs")
        mcp.resourceSkills(server = "wiki")
    }
}
```

Lets the parent agent's skill-selection LLM choose an MCP capability directly without writing per-capability wrapper skills.

## Fail-fast at construction

Connection failures throw at `agent { }` build time, not at first invocation. Catch them once in the boot sequence instead of debugging at first tool call. Hostname typo, missing auth, transport not running — all surface as `IllegalStateException` from the builder.

## Client lifecycle

Connected `McpClient`s are retained on the agent via `mcpClients` (a `WeakHashMap`-backed accessor). Useful for tests:

```kotlin
val coder = agent<X, Y>("coder") { mcp { server("github") { url = "..." } } }
coder.mcpClients.forEach { it.close() }   // tear-down
```

In production, agents typically own their MCP clients for the agent's lifetime; the `WeakHashMap` keeps the framework from leaking dead-agent state.

## Related files

- `McpClient.kt` — the connected-client type.
- `McpServer.kt` — the inverse — exposing an agent as an MCP server.
- `Agent.kt` — the host of the `mcp { }` builder slot.
- `runtime/internals/InternalsAgent.kt` — uses `McpServer` to expose this very agent.
