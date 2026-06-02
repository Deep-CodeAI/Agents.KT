package agents_engine.runtime

/** ANSI escape codes for terminal coloring. [NONE] is a no-op pass-through. */
enum class AnsiColor(val code: String) {
    NONE(""),
    BLACK("${Ansi.ESC}[30m"),
    RED("${Ansi.ESC}[31m"),
    GREEN("${Ansi.ESC}[32m"),
    YELLOW("${Ansi.ESC}[33m"),
    BLUE("${Ansi.ESC}[34m"),
    MAGENTA("${Ansi.ESC}[35m"),
    CYAN("${Ansi.ESC}[36m"),
    WHITE("${Ansi.ESC}[37m"),
    BRIGHT_BLACK("${Ansi.ESC}[90m"),
    BRIGHT_RED("${Ansi.ESC}[91m"),
    BRIGHT_GREEN("${Ansi.ESC}[92m"),
    BRIGHT_YELLOW("${Ansi.ESC}[93m"),
    BRIGHT_BLUE("${Ansi.ESC}[94m"),
    BRIGHT_MAGENTA("${Ansi.ESC}[95m"),
    BRIGHT_CYAN("${Ansi.ESC}[96m"),
    BRIGHT_WHITE("${Ansi.ESC}[97m"),
    ;

    /** Wrap [s] in this color, resetting after. Returns [s] unchanged when [code] is empty. */
    fun wrap(s: String): String = if (code.isEmpty()) s else "$code$s${Ansi.RESET}"
}
