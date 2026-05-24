---
description: Source-file knowledge for agents_engine/mcp/McpStdioServer.kt — server-side stdio MCP transport. McpStdioServer.from(agent) { expose(...), prompt(...), resource(...) }. serve(stdin, stdout) reads one line-delimited JSON-RPC envelope per stdin line, writes response envelopes only to stdout, returns no output for notifications, and reuses McpServer dispatch. Call when the IDE LLM needs to reason about exposing agents over MCP stdio.
---

# `agents_engine/mcp/McpStdioServer.kt` — expose an agent over stdio MCP

Server-side sibling to `StdioMcpTransport`. It is for processes spawned by MCP clients that speak line-delimited JSON-RPC over stdin/stdout.

## Public API

```kotlin
val server = McpStdioServer.from(agent) {
    expose("greet")
    prompt("hello", "Greeting prompt") { args -> "Hello ${args["name"]}" }
    resource("memory://note", "note", mimeType = "text/plain") { "remember this" }
}

server.serve()
```

The builder is the same `McpExposeBuilder` used by `McpServer`, so exposed skills, prompts, and resources keep identical wire behavior across HTTP and stdio.

## Framing Contract

- One UTF-8 JSON-RPC envelope per input line.
- Each request with an `id` writes exactly one response envelope plus newline.
- Requests without `id` and `notifications/*` methods write no response.
- Malformed JSON and invalid requests return JSON-RPC error envelopes with `id: null`.
- Stdout is protocol-only. Diagnostics go to stderr.

## Implementation Shape

`McpStdioServer` wraps a private `McpServer` instance and calls `dispatchJsonRpc(line)`. The stdio class owns only line reading, newline writing, flushing, and expected I/O shutdown behavior. Tool execution, prompts, resources, JSON-RPC errors, and protocol negotiation stay in `McpServer`.

## Related Files

- `McpServer.kt` — shared dispatcher and HTTP transport.
- `McpRunner.kt` — selects stdio mode with `--stdio`.
- `StdioMcpTransport.kt` — client-side stdio transport.
- `LineDelimitedMcpTransport.kt` — client-side line framing.
