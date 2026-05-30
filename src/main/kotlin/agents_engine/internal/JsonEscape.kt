package agents_engine.internal

/**
 * `agents_engine/internal/JsonEscape.kt` — single source of truth for the
 * String-to-JSON-string-literal escaping used by every provider client,
 * `InlineToolCallParser`, the snapshot serializer, the policy-manifest
 * serializer, and the @Generable runtime support. RFC 8259 §7-conformant:
 * all of U+0000-U+001F escape (with `\b` / `\f` / `\n` / `\r` / `\t` short
 * forms and `\u00XX` for the rest), plus `\` and `"`. Forward slash is
 * intentionally not escaped — it's optional per the spec and matches
 * the rest of the codebase's output.
 *
 * Before #2378 each provider client carried its own private copy that
 * only escaped `\ " \n \r \t`, producing invalid JSON for any input
 * containing NUL, `\b`, `\f`, ESC, or other U+0000-U+001F codepoints —
 * a real failure mode for binary-tool results, OCR/PDF text, and
 * captured terminal output. #2799 finished consolidating the last
 * three local escapers (generation.GenerableSupport, core.ToolPolicy
 * manifest, core.Snapshot) into this one entry point — moved to the
 * `internal` package so generation can depend on it without inverting
 * the model→generation direction.
 */
// #2799 — empty-object schema literal repeated in OpenAi / Claude / Ollama
// adapters as the fallback when a tool has no @Generable Args. Promoted to
// one constant so a change to the closed-vs-open shape (e.g. flipping
// `additionalProperties` to false) is a one-line edit. `:true` is the
// intentional opt-in for legacy untyped Map-shaped tools.
internal const val OPEN_EMPTY_OBJECT_SCHEMA_JSON: String =
    """{"type":"object","properties":{},"additionalProperties":true}"""

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
