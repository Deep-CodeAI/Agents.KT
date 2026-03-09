package agents_engine.generation

/**
 * A lenient JSON parser that tolerates common LLM output formatting issues:
 * - Markdown code fences (```json ... ```)
 * - Trailing commas before } or ]
 * - Extra explanation text before or after the JSON block
 */
internal object LenientJsonParser {

    fun parse(input: String): Any? {
        val block = extractJsonBlock(input)
        if (block.isEmpty() || (block[0] != '{' && block[0] != '[')) return null
        val json = removeTrailingCommas(block)
        return try {
            Parser(json).parseValue()
        } catch (e: Exception) {
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

        fun parseValue(): Any? {
            skipWs()
            if (pos >= s.length) return null
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // consume '{'
            val map = linkedMapOf<String, Any?>()
            skipWs()
            while (pos < s.length && s[pos] != '}') {
                skipWs()
                val key = parseString()
                skipWs()
                if (pos < s.length && s[pos] == ':') pos++
                skipWs()
                map[key] = parseValue()
                skipWs()
                if (pos < s.length && s[pos] == ',') pos++
                skipWs()
            }
            if (pos < s.length) pos++ // consume '}'
            return map
        }

        private fun parseArray(): List<Any?> {
            pos++ // consume '['
            val list = mutableListOf<Any?>()
            skipWs()
            while (pos < s.length && s[pos] != ']') {
                list.add(parseValue())
                skipWs()
                if (pos < s.length && s[pos] == ',') pos++
                skipWs()
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
                            'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
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
