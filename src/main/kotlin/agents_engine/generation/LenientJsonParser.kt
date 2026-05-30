package agents_engine.generation

/**
 * `agents_engine/generation/LenientJsonParser.kt` — JSON parser that
 * tolerates LLM output quirks (markdown fences, trailing commas,
 * pre/post explanation text). Hard-capped at 64 levels of nesting
 * to prevent `StackOverflowError` from adversarial / malformed input
 * (#854). Returns `null` on any parse failure — callers must handle
 * the absent case. See
 * `src/main/resources/internals-agent/generation/LenientJsonParser.md`
 * (#1837 / #1861).
 */

/**
 * A lenient JSON parser that tolerates common LLM output formatting issues:
 * - Markdown code fences (```json ... ```)
 * - Trailing commas before } or ]
 * - Extra explanation text before or after the JSON block
 */
// #2805 — FINE-level logger so swallowed malformed-input causes are
// recoverable in debug runs but stay out of the warn/error band.
private val LENIENT_LOGGER: java.util.logging.Logger =
    java.util.logging.Logger.getLogger("agents_engine.generation.LenientJsonParser")

internal object LenientJsonParser {

    /**
     * Hard cap on nesting depth — see #854. Without it, input like
     * `{"a":{"a":{...nested 10000 times...}}}` overflows the JVM stack
     * (`StackOverflowError` is an `Error`, not an `Exception` — it's NOT
     * caught by the try/catch in [parse]). 64 levels is comfortably more
     * than any legitimate LLM-emitted structure and keeps stack usage in
     * the kilobytes.
     */
    const val MAX_NESTING_DEPTH: Int = 64

    fun parse(input: String): Any? {
        val block = extractJsonBlock(input)
        if (block.isEmpty() || (block[0] != '{' && block[0] != '[')) return null
        val json = removeTrailingCommas(block)
        return try {
            Parser(json).parseValue()
        } catch (e: Exception) {
            // #2805 — broad catch stays (the Parser throws several distinct
            // types: IllegalStateException on depth-cap, IllegalArgumentException
            // / NumberFormatException on malformed token, etc — all documented
            // "null on malformed input" outcomes). The improvement is the FINE
            // log: the cause is now recoverable for debug runs instead of
            // silently swallowed.
            if (LENIENT_LOGGER.isLoggable(java.util.logging.Level.FINE)) {
                LENIENT_LOGGER.log(java.util.logging.Level.FINE, "parse rejected malformed input", e)
            }
            null
        }
    }

    private fun extractJsonBlock(input: String): String {
        val stripped = input
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```"), "")
        val start = stripped.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return stripped.trim()
        val end = findMatchingClose(stripped, start)
        return if (end >= 0) stripped.substring(start, end + 1) else stripped.substring(start)
    }

    private fun removeTrailingCommas(json: String): String =
        json.replace(Regex(",\\s*([}\\]])"), "$1")

    private fun findMatchingClose(s: String, openPos: Int): Int {
        val open = s[openPos]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var i = openPos
        while (i < s.length) {
            val c = s[i]
            if (c == '"' && (i == 0 || s[i - 1] != '\\')) inString = !inString
            if (!inString) {
                if (c == open) depth++
                else if (c == close) {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private class Parser(private val s: String) {
        private var pos = 0
        private var depth = 0

        fun parseValue(): Any? {
            skipWs()
            if (pos >= s.length) return null
            return when (s[pos]) {
                '{' -> withDepth { parseObject() }
                '[' -> withDepth { parseArray() }
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseNumber()
                // #1028 — refuse to fall through to parseNumber on non-numeric chars.
                // The old `else -> parseNumber()` returned 0 without advancing `pos`
                // (empty digit run), causing parseArray/parseObject to spin forever
                // on input like `[abc]`. Throw → caught by parse(input) → returns null.
                else -> throw IllegalStateException(
                    "LenientJsonParser: unexpected character '${s[pos]}' at pos $pos"
                )
            }
        }

        private inline fun <T> withDepth(block: () -> T): T {
            if (depth >= MAX_NESTING_DEPTH) {
                throw IllegalStateException("JSON nesting exceeds $MAX_NESTING_DEPTH")
            }
            depth++
            try {
                return block()
            } finally {
                depth--
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // consume '{'
            val map = linkedMapOf<String, Any?>()
            skipWs()
            while (pos < s.length && s[pos] != '}') {
                val before = pos
                skipWs()
                val key = parseString()
                skipWs()
                if (pos < s.length && s[pos] == ':') pos++
                skipWs()
                map[key] = parseValue()
                skipWs()
                if (pos < s.length && s[pos] == ',') pos++
                skipWs()
                // #1028 — defense-in-depth: refuse to spin if no progress was made.
                if (pos == before) {
                    throw IllegalStateException(
                        "LenientJsonParser: zero-progress at pos $pos in object"
                    )
                }
            }
            if (pos < s.length) pos++ // consume '}'
            return map
        }

        private fun parseArray(): List<Any?> {
            pos++ // consume '['
            val list = mutableListOf<Any?>()
            skipWs()
            while (pos < s.length && s[pos] != ']') {
                val before = pos
                list.add(parseValue())
                skipWs()
                if (pos < s.length && s[pos] == ',') pos++
                skipWs()
                // #1028 — defense-in-depth: refuse to spin if no progress was made.
                if (pos == before) {
                    throw IllegalStateException(
                        "LenientJsonParser: zero-progress at pos $pos in array"
                    )
                }
            }
            if (pos < s.length) pos++ // consume ']'
            return list
        }

        private fun parseString(): String {
            if (pos < s.length && s[pos] == '"') pos++ // consume opening '"'
            val sb = StringBuilder()
            while (pos < s.length && s[pos] != '"') {
                if (s[pos] == '\\' && pos + 1 < s.length) {
                    pos++
                    sb.append(
                        when (s[pos]) {
                            '"' -> '"'; '\\' -> '\\'; '/' -> '/'
                            'b' -> '\b'; 'f' -> '\u000C'
                            'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                            'u' -> parseUnicodeEscape() ?: 'u'
                            else -> s[pos]
                        }
                    )
                } else {
                    sb.append(s[pos])
                }
                pos++
            }
            if (pos < s.length) pos++ // consume closing '"'
            return sb.toString()
        }

        private fun parseUnicodeEscape(): Char? {
            if (pos + 4 >= s.length) return null
            val hex = s.substring(pos + 1, pos + 5)
            if (hex.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null
            pos += 4
            return hex.toInt(16).toChar()
        }

        private fun parseBoolean(): Boolean =
            if (s.startsWith("true", pos)) { pos += 4; true } else { pos += 5; false }

        private fun parseNull(): Nothing? { pos += 4; return null }

        private fun parseNumber(): Number {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            if (pos < s.length && s[pos] == '.') {
                pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            val n = s.substring(start, pos)
            if (n.isEmpty() || n == "-") return 0
            return if ('.' in n || 'e' in n.lowercase()) {
                n.toDoubleOrNull() ?: 0.0
            } else {
                val l = n.toLongOrNull() ?: return 0
                if (l >= Int.MIN_VALUE && l <= Int.MAX_VALUE) l.toInt() else l
            }
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
    }
}
