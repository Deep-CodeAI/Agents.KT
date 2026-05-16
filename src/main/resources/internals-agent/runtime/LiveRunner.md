# `agents_engine/runtime/LiveRunner.kt` — one-line REPL main

The picocli-shaped CLI wrapper around `LiveShow`. Sibling to `McpRunner` (which exposes an agent over MCP rather than over an interactive prompt).

## Usage

```kotlin
fun main(args: Array<String>) = exitProcess(
    LiveRunner.serve(coder, args) {
        prompt = "coder> "
        precheck = OllamaPreflight()::check
    },
)
```

`serve` returns the process exit code.

## CLI flags

| Flag | Effect |
|---|---|
| `--once "<prompt>"` | Non-interactive: run one invocation with the given prompt, write output to stdout, exit. Useful for scripting / shell pipelines. |
| `--max-history N` | Override the builder's `maxHistoryTurns` (history-trimming threshold). |
| `-h` / `--help` | Print usage block, return 0. |
| `-V` / `--version` | Print framework version, return 0. |

Block defaults can be overridden by CLI flags when both are given.

## Lifecycle

`LiveRunner.serve(agent, args, block)`:
1. Builds a `LiveShow` from `block`.
2. Parses `args` for flags; applies overrides.
3. Runs the REPL via `runBlocking { liveShow.run() }`.
4. Returns the appropriate exit code.

## Single-shot mode

When `--once "<prompt>"` is set, the REPL runs the prompt through the agent once, writes the output to stdout (no prompt prefix, no banner), and exits. Designed to slot into shell pipelines:

```bash
echo "fix the failing test" | java -jar my-agent.jar --once "$(cat -)"
```

## Related files

- `LiveShow.kt` — the REPL surface this wraps.
- `mcp/McpRunner.kt` — the parallel runner for MCP serving.
- `model/OllamaPreflight.kt` — typical `precheck` value.
