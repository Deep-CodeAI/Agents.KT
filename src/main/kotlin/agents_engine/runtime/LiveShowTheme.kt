package agents_engine.runtime

/** Color theme for the LiveShow REPL. Roles map to ANSI colors; [NONE] disables all. */
data class LiveShowTheme(
    val prompt: AnsiColor,
    val agentOutput: AnsiColor,
    val error: AnsiColor,
    val slashOutput: AnsiColor,
    val banner: AnsiColor,
) {
    companion object {
        /** Pink/cyan/green default — magenta for the banner echoes the logo's accent. */
        val DEFAULT = LiveShowTheme(
            prompt = AnsiColor.BRIGHT_CYAN,
            agentOutput = AnsiColor.BRIGHT_GREEN,
            error = AnsiColor.RED,
            slashOutput = AnsiColor.YELLOW,
            banner = AnsiColor.BRIGHT_MAGENTA,
        )

        /** Plain text for every role — escape-code-free output. */
        val NONE = LiveShowTheme(
            prompt = AnsiColor.NONE,
            agentOutput = AnsiColor.NONE,
            error = AnsiColor.NONE,
            slashOutput = AnsiColor.NONE,
            banner = AnsiColor.NONE,
        )
    }
}
