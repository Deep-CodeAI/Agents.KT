---
description: Source-file knowledge for agents_engine/mcp/McpRunner.kt — McpRunner.serve(agent, args) { } one-line main returning exit code. CLI flags: --port N (0=auto), --stdio, --expose NAME (repeatable, overrides block exposes), -h/--help, -V/--version. HTTP mode uses CountDownLatch-based graceful shutdown; stdio mode keeps stdout protocol-only and returns on stdin EOF. Sibling to LiveRunner. Call when the IDE LLM needs to reason about exposing an agent over MCP from a CLI.
---

# `agents_engine/mcp/McpRunner.kt` — one-line MCP main

Wraps the entire "expose an agent over MCP" sequence into a single helper.

## Usage

```kotlin
fun main(args: Array<String>) = exitProcess(McpRunner.serve(coder, args) {
    port = 8080
    expose("write-code", "review-code")
})
```

Returns the process exit code (0 on clean shutdown, non-zero on error or `--help`).

## CLI flags

The block sets defaults; CLI flags override them.

| Flag | Effect |
|---|---|
| `--port N` | Bind port. `0` = OS-assigned. Default: from block, then `0`. |
| `--stdio` | Serve line-delimited JSON-RPC over stdin/stdout instead of binding HTTP. |
| `--expose NAME` | Skill to expose (repeatable). If any `--expose` is passed, REPLACES the block's `expose(...)` calls. |
| `-h` / `--help` | Print usage and return `0`. |
| `-V` / `--version` | Print Agents.KT version and return `0`. |

Picocli-shaped (consistent with `LiveRunner`).

## Block configuration

```kotlin
McpRunner.serve(agent, args) {
    port = 8080                  // bind port
    stdio = false                // true = serve stdin/stdout
    expose("foo", "bar")         // skills to expose
}
```

The block runs against a builder of the same shape as `McpServer.from(agent) { }` — same fields, same semantics.

## Lifecycle

HTTP mode builds the `McpServer`, starts it, prints the listening URL to stdout, and blocks on a `CountDownLatch` until SIGTERM / SIGINT. The latch is wired to a shutdown hook that calls `server.stop()` gracefully.

Stdio mode builds `McpStdioServer`, reads one JSON-RPC envelope per stdin line, writes response envelopes to stdout, and returns when stdin closes. It does not print banners or listening text to stdout because MCP stdio clients treat stdout as protocol traffic.

## Help / version output

`--help` prints the usage block (flags + defaults). `--version` prints the framework version constant (`McpRunner.VERSION`, currently `"0.3.0"` — note: this is the runner's own version, separate from the framework release version).

## Related files

- `McpServer.kt` — what the runner wraps.
- `McpStdioServer.kt` — stdio transport selected by `--stdio`.
- `McpServerInfo.kt` — what `--help` references for protocol version.
- `runtime/LiveRunner.kt` — the sibling runner for REPL serving.
- `runtime/internals/Main.kt` — uses `McpServer.from` directly rather than `McpRunner`, because the InternalsAgent picks its own port from args and exposes every skill automatically.
