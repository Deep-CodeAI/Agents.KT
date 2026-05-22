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
