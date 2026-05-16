# `agents_engine/runtime/LiveShow.kt` — interactive demo REPL

The line-by-line REPL behind every demo in the repo. Builds a colorful, themable interactive runner around any of the six top-level types: `Agent`, `Pipeline`, `Branch`, `Loop`, `Parallel`, `Forum`.

## Quick example

```kotlin
fun main() {
    LiveShow {
        agent = coderAgent
        prompt = "coder> "
        title = "Coder Agent"
        banner = bannerOf("CODER")
        theme = Theme.OCEAN
        precheck = OllamaPreflight()::check
        maxHistoryTurns = 30
        onCommand("/help") { sayHelp() }
    }.run()
}
```

## UI surface (#983)

- **ANSI color** — `AnsiColor` enum + `Style(fg, bg, bold, dim, italic, underline)` data class. `NONE` is a no-op pass-through; the REPL silently degrades when stdout isn't a TTY.
- **Themes** — `Theme.NONE` (monochrome), `Theme.OCEAN`, `Theme.FOREST`, etc. Each theme is a `Map<Role, Style>` (prompt color, output color, banner color, error color, spinner color).
- **ASCII banner** — multi-line greeting printed once at startup.
- **Spinner** — animated `⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏` while the agent thinks. Goes away when output starts.
- **Hooks** — `onCommand("/foo") { ... }` lets the user register slash-commands handled by the host (e.g., `/clear`, `/save`, `/quit`).
- **Precheck** — optional `() -> Unit` invoked at startup. Throwing aborts the REPL with a clear error before any prompt is drawn (typical: `OllamaPreflight`).

## History trimming

`maxHistoryTurns` controls the history-trimming threshold. Useful for long-running REPLs where the conversation would otherwise grow unbounded. Default is some sensible value; pass `Int.MAX_VALUE` to disable.

## Reader / Writer abstraction

LiveShow takes a `Reader` (default: `System.in`) and a `PrintWriter` (default: `System.out`). Tests inject fake streams to drive the REPL without a real terminal.

## Lifecycle

`LiveShow.run()`:
1. Run `precheck()` if set; abort on failure.
2. Print the banner.
3. Loop: print prompt → read line → check for slash-command hook → invoke agent → print output → repeat.
4. `Ctrl+D` (EOF) or `/quit` terminates.

The framework holds the agent's invocation in a `runBlocking` per turn — single-threaded by design (one user, one agent, one turn at a time).

## Related files

- `LiveRunner.kt` — the CLI wrapper.
- `model/OllamaPreflight.kt` — typical `precheck` value.
- All composition operators — accepted as the `agent` / `pipeline` slot.
