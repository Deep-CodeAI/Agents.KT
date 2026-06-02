package agents_engine.manifest

internal object StableJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        is String -> quote(value)
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .joinToString(",", "{", "}") { (key, mapValue) ->
                "${quote(key.toString())}:${encode(mapValue)}"
            }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> quote(value.toString())
    }

    private fun quote(value: String): String =
        buildString(value.length + 2) {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch < ' ') {
                        append("\\u${ch.code.toString(16).padStart(4, '0')}")
                    } else {
                        append(ch)
                    }
                }
            }
            append('"')
        }
}
