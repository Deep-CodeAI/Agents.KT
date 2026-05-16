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
| `--expose NAME` | Skill to expose (repeatable). If any `--expose` is passed, REPLACES the block's `expose(...)` calls. |
| `-h` / `--help` | Print usage and return `0`. |
| `-V` / `--version` | Print Agents.KT version and return `0`. |

Picocli-shaped (consistent with `LiveRunner`).

## Block configuration

```kotlin
McpRunner.serve(agent, args) {
    port = 8080                  // bind port
    expose("foo", "bar")         // skills to expose
}
```

The block runs against a builder of the same shape as `McpServer.from(agent) { }` — same fields, same semantics.

## Lifecycle

`serve` builds the `McpServer`, starts it, blocks on a `CountDownLatch` until SIGTERM / SIGINT. The latch is wired to a shutdown hook that calls `server.stop()` gracefully.

## Help / version output

`--help` prints the usage block (flags + defaults). `--version` prints the framework version constant (`McpRunner.VERSION`, currently `"0.3.0"` — note: this is the runner's own version, separate from the framework release version).

## Related files

- `McpServer.kt` — what the runner wraps.
- `McpServerInfo.kt` — what `--help` references for protocol version.
- `runtime/LiveRunner.kt` — the sibling runner for REPL serving.
- `runtime/internals/Main.kt` — uses `McpServer.from` directly rather than `McpRunner`, because the InternalsAgent picks its own port from args and exposes every skill automatically.
