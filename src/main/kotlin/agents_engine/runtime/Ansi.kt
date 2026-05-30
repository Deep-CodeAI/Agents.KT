package agents_engine.runtime

/**
 * `agents_engine/runtime/Ansi.kt` — one source of truth for the ANSI
 * escape sequences sprayed across `LiveShow.kt` and its spinner (#2806).
 * Before the consolidation, raw escape strings (`"[0m"`,
 * `"[2K"`) and a dead `RESET` constant inside `AnsiColor.Companion`
 * lived side by side — the `RESET` const was defined but the `wrap`
 * function hardcoded `"[0m"` inline, so the constant only existed
 * to mislead readers about where to change reset behavior.
 *
 * Everything that emits an ANSI escape on the runtime side goes through
 * this object now. Keep it `internal` — outside callers should compose
 * via [AnsiColor] or stay theme-agnostic.
 */
internal object Ansi {
    /** Escape introducer (CSI prefix is `ESC[`). */
    const val ESC: String = ""

    /** Reset all attributes — terminates every styled run. */
    const val RESET: String = "$ESC[0m"

    /** Erase from cursor to end of line — used after a `\r` to clear the spinner frame. */
    const val ERASE_LINE: String = "$ESC[2K"
}
