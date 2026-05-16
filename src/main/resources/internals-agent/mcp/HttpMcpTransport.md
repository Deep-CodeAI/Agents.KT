# `agents_engine/mcp/HttpMcpTransport.kt` — Streamable HTTP transport

The MCP spec's primary transport. POST request → JSON or SSE response.

## API

```kotlin
internal class HttpMcpTransport(
    private val url: String,
    private val auth: McpAuth = McpAuth.None,
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) : McpTransport
```

## Request shape

Each `rpc(envelope)` is a `POST $url` with:
- `Content-Type: application/json`
- `Authorization: Bearer <token>` when `auth = Bearer(...)` (redacted in logs)
- The JSON-RPC envelope as the body

`notify(envelope)` is the same but discards the response.

## Response shapes

Two acceptable response shapes:
- **JSON body** — `Content-Type: application/json`, full envelope returned as a string.
- **SSE stream** — `Content-Type: text/event-stream`, parser extracts a single `data: ...` event. Useful for servers that stream long responses.

The transport chooses based on `Content-Type`; consumers see only the final JSON envelope string either way.

## Session-id capture

MCP servers may issue a session identifier via `Mcp-Session-Id` response header. The transport captures the first one it sees and replays it as `Mcp-Session-Id` on every subsequent request — lets stateful servers track multi-request sessions.

## Limits

- `requestTimeout` — JDK `HttpClient` request deadline.
- `maxResponseBytes` — caps the response size for SSE and JSON to prevent memory blowup on adversarial / runaway servers.

## Shared HttpClient

The JDK `HttpClient` is process-wide — `close()` doesn't tear it down. The transport's `close()` is a no-op for that reason; the only per-transport state is the session id.

## Related files

- `McpTransport.kt` — the interface.
- `McpAuth.kt` — Bearer-token shape.
- `McpClient.kt` — the consumer.
- `AgentMcpDsl.kt` — wires this transport for `url = "..."` registrations.
