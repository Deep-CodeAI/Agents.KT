---
description: Source-file knowledge for agents_engine/mcp/TcpMcpTransport.kt — thin subclass of LineDelimitedMcpTransport delegating to the socket's streams. close() runCatching { socket.close() } for idempotence (peer may have closed). Caller builds the Socket; transport owns its lifetime. No built-in TLS or auth — use HTTPS-fronted HTTP transport for untrusted networks. Call when the IDE LLM needs to reason about TCP MCP connectivity.
---

# `agents_engine/mcp/TcpMcpTransport.kt` — TCP MCP transport

A thin subclass of `LineDelimitedMcpTransport` that delegates framing to its parent and adds socket lifecycle.

## Shape

```kotlin
internal class TcpMcpTransport(private val socket: Socket) :
    LineDelimitedMcpTransport(socket.getInputStream(), socket.getOutputStream()) {

    override fun close() {
        super.close()
        runCatching { socket.close() }
    }
}
```

That's the entire file.

## When to use

- **Localhost-only servers** — sidecars in the same pod, daemons on the same machine.
- **Trusted networks** — k8s pod-to-pod, internal-only VLAN.
- **No HTTP overhead needed** — line-delimited JSON is lighter than HTTP for high-RPC-rate scenarios.

For untrusted networks, use HTTPS-fronted HTTP transport instead — TCP transport has no built-in TLS or auth (see `McpAuth.md`).

## Construction

The caller builds the `Socket` and passes it in — connection details (host, port, SO_LINGER, etc.) live with the caller. Once handed to the transport, the transport owns the socket's lifetime and closes it in `close()`.

```kotlin
val socket = Socket("mcp.internal", 9000)
val transport = TcpMcpTransport(socket)
val client = McpClient(transport)
```

`AgentMcpDsl.kt` wires this for `server { host = "..."; port = N }` registrations.

## `runCatching` on close

`socket.close()` is wrapped in `runCatching` because:
- The socket may already be closed by the peer (broken pipe).
- The user may have closed it externally before `close()` runs.

Swallowing the exception is the right call here — `close()` is idempotent and shouldn't throw.

## Related files

- `LineDelimitedMcpTransport.kt` — provides framing.
- `McpTransport.kt` — the interface.
- `AgentMcpDsl.kt` — wires this for `host + port` registrations.
