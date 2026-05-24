package agents_engine.model

/**
 * `agents_engine/model/JsonEscape.kt` — single source of truth for the
 * String-to-JSON-string-literal escaping used by every provider client
 * and by `InlineToolCallParser`. RFC 8259 §7-conformant: all of
 * U+0000-U+001F escape (with `\b` / `\f` / `\n` / `\r` / `\t` short
 * forms and `\u00XX` for the rest), plus `\` and `"`. Forward slash is
 * intentionally not escaped — it's optional per the spec and matches
 * the rest of the codebase's output.
 *
 * Before #2378 each provider client carried its own private copy that
 * only escaped `\ " \n \r \t`, producing invalid JSON for any input
 * containing NUL, `\b`, `\f`, ESC, or other U+0000-U+001F codepoints —
 * a real failure mode for binary-tool results, OCR/PDF text, and
 * captured terminal output.
 */
internal fun String.toJsonString(): String = buildString(length + 2) {
    append('"')
    for (ch in this@toJsonString) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (ch.code < 0x20) append("\\u%04x".format(ch.code))
                else append(ch)
            }
        }
    }
    append('"')
}
