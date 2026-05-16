---
description: Source-file knowledge for agents_engine/mcp/LineDelimitedMcpTransport.kt — abstract base for stdio + TCP transports. \n-terminated UTF-8 JSON-RPC envelopes. Notifications (no id field) dropped silently. Single-flight: callers must serialize rpc() calls. Subclasses override only close() for their own teardown. Call when the IDE LLM needs to reason about line-delimited MCP framing.
---

# `agents_engine/mcp/LineDelimitedMcpTransport.kt` — base for stdio + TCP

Shared logic for the two line-delimited transports.

## Framing

- One JSON-RPC envelope per line.
- Lines are `\n`-terminated.
- UTF-8.
- Reader / writer are buffered.

`rpc(envelope)` writes one line, blocks on reading the response. `notify(envelope)` writes one line and returns.

## Notification skipping

Server-emitted messages with no `id` field (notifications, per JSON-RPC spec) are dropped silently. Only `id`-matched responses are returned to callers. The transport tracks the expected id (extracted from the outgoing envelope) and skips through notifications until it sees a response with that id.

This matters because some servers stream progress notifications during long operations; consumers don't currently need them and dropping them silently is the lowest-friction handling.

## Single-flight contract

`rpc(envelope)` is NOT thread-safe. Callers must serialize their calls. The `McpClient` is single-threaded — so the contract matches.

If a future use case needs concurrent RPCs, the transport will need:
- Per-id pending-response futures.
- A dispatcher thread reading the response stream and matching ids.

Today, single-flight keeps the implementation tiny.

## Two subclasses

- `StdioMcpTransport(input: InputStream, output: OutputStream, onClose: () -> Unit)` — pipe to a child process or in-test streams.
- `TcpMcpTransport(socket: Socket)` — TCP socket; `close()` closes the socket too.

Both delegate framing entirely to this base; they only override `close()` for their own teardown.

## Related files

- `McpTransport.kt` — the interface.
- `StdioMcpTransport.kt`, `TcpMcpTransport.kt` — the concrete subclasses.
- `generation/LenientJsonParser.kt` — used to extract the `id` from envelopes for the notification-skip logic.
