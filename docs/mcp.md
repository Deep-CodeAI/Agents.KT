[← Back to README](../README.md)

## MCP Integration

Agents.KT speaks MCP in **both directions**: an agent can consume tools from any MCP server, and any agent can expose its skills as an MCP server that Claude Code, Cursor, or any MCP-aware client can call. Three transports — HTTP, stdio, TCP — share one wire format. Zero new dependencies; all on JDK 21.

### Consuming MCP servers — `mcp { server() }`

```kotlin
val coder = agent<String, String>("coder") {
    mcp {
        server("github") {
            url = "https://api.github.com/mcp"
            auth = McpAuth.Bearer(System.getenv("GITHUB_TOKEN"))
        }
        server("filesystem") {
            command = listOf("npx", "@modelcontextprotocol/server-filesystem", "/src")
        }
        server("internal") { host = "mcp.internal"; port = 9000 }
    }
    skills {
        skill<String, String>("work", "Use the registered tools") {
            tools("github.create_pull_request", "filesystem.read_file", "internal.foo")
        }
    }
}
```

Each `server(name) { }` declares **exactly one** transport (`url=` xor `command=` xor `host=`+`port=`), connects at agent-build time, and registers the discovered tools into the agent's `toolMap` with names prefixed by the server name. Collisions across servers can't happen — the prefix is the namespace.

**`mcp { }`** — block on the agent, takes one or more `server(name) { }` sub-blocks. Failures fail the agent build (fail fast).

**`McpAuth.Bearer(token)`** — HTTP-only auth. Stdio and TCP derive auth from connection identity. OAuth 2.1 is on the roadmap.

**`agent.mcpClients`** — connected clients for lifecycle control (`close()` in tests).

For lower-level integrations, `McpClient` exposes discovered server tools in two direct forms:

```kotlin
val client = McpClient.connect(server.url)

val skillShape = client.toolSkills().single() // Skill<Map<String, Any?>, String>
val toolShape = client.tools().single()       // McpTool<Map<String, Any?>, String>

val result = toolShape.call(mapOf("input" to "go"))
```

Use `toolSkills()` when the MCP capability is itself a primary agent skill. Use `tools()` when you need a provider-neutral `Tool<*, *>` boundary object for grants, manifests, policy, or audit code. Both call the same MCP `tools/call` endpoint.

### Exposing an agent as an MCP server — `McpServer.from(agent)`

```kotlin
@Generable("Person being greeted")
data class GreetRequest(@Guide("Name") val name: String, @Guide("Lang") val language: String = "en")

val greeter = agent<GreetRequest, String>("greeter") {
    skills {
        skill<GreetRequest, String>("greet", "Greet a person") {
            implementedBy { req -> "[${req.language}] Hello, ${req.name}!" }
        }
    }
}

val server = McpServer.from(greeter) {
    port = 8080         // 0 = auto-assigned
    expose("greet")
}.start()

println(server.url)     // http://localhost:8080/mcp
```

Exposed skills become MCP tools. The `inputSchema` is generated from the skill's `IN` type via `@Generable` reflection — the JSON schema includes `@Guide` descriptions so the calling LLM knows what each field means.

HTTP servers default to trusted-local mode: loopback callers can connect without credentials, and non-loopback callers are rejected unless you configure explicit auth. For a network-reachable endpoint, set `auth`, `allowedHosts`, `originAllowlist`, and a per-principal `toolPolicy`:

```kotlin
val server = McpServer.from(greeter) {
    port = 8080
    expose("greet")
    auth = McpServerAuth.RequireBearerTokens(
        mapOf(
            requireNotNull(System.getenv("MCP_READ_TOKEN")) to ClientPrincipal("ide-readonly"),
            requireNotNull(System.getenv("MCP_ADMIN_TOKEN")) to ClientPrincipal("ide-admin"),
        ),
    )
    allowedHosts = setOf("agents.internal.example")
    originAllowlist = setOf("https://ide.internal.example")
    toolPolicy { principal, toolName ->
        principal.id == "ide-admin" || toolName == "greet"
    }
}
```

Denied tools are filtered out of `tools/list`; denied `tools/call` requests return a generic `-32601` JSON-RPC error so the server does not confirm that the tool exists. See [MCP Server Hardening](mcp-server.md) for gateway examples.

### How external clients consume your `McpServer`

| Client | How |
|--------|-----|
| Our own `McpClient` | `McpClient.connect(server.url)` then `client.call("greet", mapOf("name" to "Kon"))` |
| **Claude Code** | Add `{"mcpServers": {"my-agent": {"type": "http", "url": "http://localhost:8080/mcp"}}}` to `~/.claude.json` and restart |
| Cursor / IDEs | Same URL, the IDE's MCP config block |
| Anything that speaks MCP | Standard JSON-RPC 2.0 over Streamable HTTP, protocol version `2025-03-26` |

### Stdio server transport — `McpStdioServer.from(agent)`

Use stdio when the MCP client wants to spawn your agent process directly instead of connecting to an HTTP port. The registration DSL is the same as `McpServer`: exposed skills become tools, and registered prompts/resources use the same JSON-RPC handlers.

```kotlin
McpStdioServer.from(greeter) {
    expose("greet")
}.serve()
```

Stdio framing is one UTF-8 JSON-RPC envelope per line. Requests with no `id` and `notifications/*` methods produce no response. Malformed input is returned as a JSON-RPC error envelope with `id: null`. `stdout` is protocol-only; diagnostics belong on `stderr`.

Example client config for a JAR:

```json
{
  "mcpServers": {
    "my-agent": {
      "command": "java",
      "args": ["-jar", "/path/to/my-agent.jar", "--stdio"]
    }
  }
}
```

### Standalone server with `McpRunner` — picocli-style one-liner main

Wrap any agent in a real runnable JAR with one line:

```kotlin
fun main(args: Array<String>) = exitProcess(McpRunner.serve(greeter, args) {
    port = 8080                        // overridden by --port
    expose("greet")                    // overridden by --expose (repeatable)
})
```

The runner parses CLI args and serves HTTP by default: it builds the `McpServer`, prints the listening URL, registers a JVM shutdown hook for graceful `stop()`, and blocks until SIGTERM/SIGINT. With `--stdio`, it builds `McpStdioServer`, reads line-delimited JSON-RPC from stdin, writes only protocol responses to stdout, and returns when stdin closes.

Flags: `--port N`, `--stdio`, `--expose NAME` (repeatable), `-h/--help`, `-V/--version`. Hand-rolled CLI parser, zero new dependencies.

### Three ways to run an agent — library, hosted, autonomous

Same agent, three deployment modes; each is one line of glue away from the next:

| Mode | Glue | Where it runs | Who can call it |
|------|------|---------------|----------------|
| **Library** | `agent<IN, OUT>("...") { skills { ... } }` | In your JVM, in-process | Your Kotlin code, fully typed |
| **Hosted** | + `McpServer.from(agent) { expose("...") }.start()` | In your JVM, plus an MCP endpoint | Internal callers (typed) AND any MCP client |
| **Autonomous** | `fun main(args) = exitProcess(McpRunner.serve(agent, args))` | Its own process / JAR / Docker / native binary | Any MCP client, anywhere |

You don't pick once — you can **eject** the agent into autonomy when independent scale matters. See [Agent Deployment Modes](https://github.com/Deep-CodeAI/Agents.KT/wiki/Agent-Deployment-Modes) for the full progression and tradeoffs.

See the [MCP Integration](https://github.com/Deep-CodeAI/Agents.KT/wiki/MCP-Integration) wiki page for the full DSL surface, lower-level `McpClient` factories, in-process mock servers for hermetic tests, and protocol-version handling.

---

## Related: A2A

Any agent served over MCP can simultaneously be served over A2A — different protocol shapes of the same instance (skills-as-tools vs one typed agent endpoint). See [a2a.md → Serving MCP and A2A side by side](a2a.md#serving-mcp-and-a2a-side-by-side).
