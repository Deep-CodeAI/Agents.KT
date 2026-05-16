# `agents_engine/mcp/StdioMcpTransport.kt` — stdio MCP transport

Line-delimited JSON over stdin/stdout. Two factory paths.

## `forStreams(input, output, onClose)`

Wraps a generic input/output stream pair. Used by:
- Tests — in-process pipes (`PipedInputStream` / `PipedOutputStream`).
- Custom IPC channels — anyone wiring named pipes, Unix sockets-via-streams, etc.

`onClose` is invoked from `close()` — wire it to teardown the underlying resources.

## `forProcess(command, env, workingDir, stderrSink)`

Spawns a child process via `ProcessBuilder` and pipes:
- Child's stdout → transport's input
- Child's stdin ← transport's output
- Child's stderr → `stderrSink(line)` lambda (default: drop)

Standard way to consume MCP servers shipped as binaries:

```kotlin
StdioMcpTransport.forProcess(
    command = listOf("npx", "@modelcontextprotocol/server-filesystem", "/src"),
    env = mapOf("DEBUG" to "1"),
    workingDir = File("/tmp/workdir"),
    stderrSink = { line -> log.debug("server stderr: $line") },
)
```

## stderr drain thread

Subprocesses produce stderr unpredictably. If we don't drain it, the OS-level pipe buffer fills and the child blocks waiting for someone to read — deadlock.

The factory starts a daemon thread (`MCP-stdio-stderr-<pid>`) that reads stderr line-by-line forever and forwards each line to `stderrSink`. Daemon so the JVM doesn't wait for it on shutdown.

## Graceful shutdown

`close()` runs the `onClose` lambda, which for `forProcess`:
1. `process.destroy()` — SIGTERM equivalent.
2. `process.waitFor(2, TimeUnit.SECONDS)` — give the child 2 seconds.
3. If still alive: `process.destroyForcibly()` — SIGKILL equivalent.
4. `stderrThread.join(500)` — let the drain thread drain final lines.

Two seconds is short enough that a hung child doesn't slow down test teardown, long enough that well-behaved children get to flush.

## Race against fast-exit (Linux CI)

A documented test flake: on Linux CI, a child can exit before the parent's test code reaches the read. The `LineDelimitedMcpTransport` notification-skip logic + the drain thread together make the transport robust to this — fast-exits surface as EOF on `rpc()` which throws cleanly rather than hanging.

## Related files

- `LineDelimitedMcpTransport.kt` — provides the line framing.
- `McpTransport.kt` — the interface.
- `AgentMcpDsl.kt` — wires this transport for `command = listOf(...)` registrations.
