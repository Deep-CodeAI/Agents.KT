package agents_engine.mcp

/**
 * `agents_engine/mcp/McpJson.kt` — minimal internal JSON encoder for
 * MCP envelopes. Strict output (no trailing commas), handles null /
 * Boolean / Number / String / Map / Iterable / Array. Full string
 * escape coverage including `\uXXXX` for control characters under
 * 0x20. Used to build JSON-RPC requests; the framework's lenient
 * parser is used to read responses. See
 * `src/main/resources/internals-agent/mcp/McpJson.md` (#1837 / #1881).
 */

internal object McpJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> value.toString()
        is String -> escape(value)
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") {
            escape(it.key.toString()) + ":" + encode(it.value)
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> escape(value.toString())
    }

    private fun escape(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}
