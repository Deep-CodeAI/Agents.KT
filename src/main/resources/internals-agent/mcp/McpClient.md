# `agents_engine/mcp/McpClient.kt` — MCP client

Wraps an `McpTransport` and speaks JSON-RPC. The single point of contact with a remote MCP server.

## Lifecycle

```kotlin
val client = McpClient.http(url = "...", auth = ...)    // or .stdio(...) / .tcp(...)
client.handshake()                                       // sets serverProtocolVersion / serverName / serverVersion
client.loadTools()                                       // populates tool list
// v0.5.0+: also call loadPrompts() / loadResources() / loadResourceTemplates() as needed
client.snapshot                                          // McpServerInfo with everything fetched so far
client.close()
```

`handshake()` sends the JSON-RPC `initialize` request, records what the server reports. `loadTools()` calls `tools/list` and caches the tool descriptors.

## `snapshot: McpServerInfo?` (#1734)

After handshake + listings, `snapshot` carries the pure-data view of the server's surface. Consumers read off this single shape rather than scattered `serverName` / `serverVersion` / private tool accessors. Fields the client doesn't currently fetch (capabilities matrix beyond tool support, etc.) remain null/default; later RPC calls will fill them in.

## Tool invocation

The client exposes each MCP tool as a `ToolDef` via the agent's tool map (prefixed by server name; see `AgentMcpDsl.md`). Each tool's executor JSON-RPCs `tools/call` to the server and returns the result. Argument deserialization uses the LenientJsonParser; result serialization uses `McpJson`.

## Synchronous

The client is single-threaded; transports are single-flight. Concurrent invocations from the agentic loop are serialized externally if needed. For agents that expect concurrent tool calls against the same MCP server, use one client per concurrent path or wrap the calls.

## Constructors

`McpClient` itself has `internal` constructor; factories live in companion methods:
- `McpClient.http(url, auth, timeouts)` → wraps `HttpMcpTransport`.
- `McpClient.stdio(command, env, workingDir, stderrSink)` → spawns a subprocess via `StdioMcpTransport.forProcess`.
- `McpClient.tcp(socket)` → wraps `TcpMcpTransport`.

## `AutoCloseable`

`close()` tears down the transport. For HTTP that's a no-op; for stdio it kills the subprocess; for TCP it closes the socket. Always call (use `use { }` blocks in tests).

## Atomic id counter

`nextId: AtomicLong = AtomicLong(2)` — request IDs start at 2 (the initialize handshake uses 1). Atomic so the counter is safe even though tool invocations through the client are externally serialized.

## Related files

- `McpTransport.kt` — the wire interface.
- `McpServerInfo.kt` — the snapshot shape.
- `AgentMcpDsl.kt` — the constructor consumers in the agent DSL.
- `McpJson.kt`, `generation/LenientJsonParser.kt` — wire encoding / parsing.
