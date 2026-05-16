---
description: Source-file knowledge for agents_engine/mcp/McpTransport.kt — internal interface. rpc(envelope): String for request/response, notify(envelope) for fire-and-forget. AutoCloseable. Single-flight (callers serialize). Three implementations: HttpMcpTransport (Streamable HTTP), TcpMcpTransport (line-delimited TCP), StdioMcpTransport (line-delimited stdio). Same JSON-RPC envelope, different framing. Call when the IDE LLM needs to reason about MCP transports.
---

# `agents_engine/mcp/McpTransport.kt` — MCP wire transport interface

The seam between `McpClient` and the three concrete transports.

## Interface

```kotlin
internal interface McpTransport : AutoCloseable {
    fun rpc(envelope: String): String           // request → response
    fun notify(envelope: String)                // request, no response expected
    override fun close()
}
```

- `internal` — consumers go through `McpClient`, not the transport directly.
- Synchronous. Both `rpc` and `notify` block on I/O.
- Single-flight. Callers must serialize their calls.

## Three implementations

| Class | Wire format | Use |
|---|---|---|
| `HttpMcpTransport` | Streamable HTTP (POST + JSON or SSE) | Remote MCP servers. |
| `TcpMcpTransport` | Line-delimited JSON over a TCP socket | Local / VPN servers. |
| `StdioMcpTransport` | Line-delimited JSON over stdin/stdout | Subprocess servers (`npx`, etc.). |

The JSON-RPC envelope is identical across all three — only the framing differs.

## Why an interface

Two reasons:

1. **Pluggable framing** — HTTP and stdio have very different I/O models (request/response vs. continuous stream). The interface lets `McpClient` stay framing-agnostic.
2. **Test seam** — tests can plug in a fake `McpTransport` that returns canned envelopes without touching real I/O.

## `AutoCloseable`

`close()` is idempotent in practice:
- HTTP: no-op (JDK `HttpClient` is shared).
- TCP: closes the socket; second close is a no-op runCatching.
- Stdio: kills the subprocess; second close is a no-op runCatching.

Use `client.use { ... }` blocks in tests to guarantee cleanup.

## Related files

- `HttpMcpTransport.kt`, `TcpMcpTransport.kt`, `StdioMcpTransport.kt` — the three implementations.
- `LineDelimitedMcpTransport.kt` — base class for the two line-delimited variants.
- `McpClient.kt` — the consumer.
