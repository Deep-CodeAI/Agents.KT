package agents_engine.speechserver

/**
 * #4506 — tiny JSON helpers for the `/v1/audio/speech` request and the
 * `/v1/audio/transcriptions` response. Deliberately minimal (the server depends on
 * nothing): extract a string field, and escape a string for output.
 */

/** Extract the string value of [key] from a flat JSON object, or null. Unescapes the value. */
internal fun jsonStringField(json: String, key: String): String? {
    val regex = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    return regex.find(json)?.groupValues?.get(1)?.let(::unescapeJson)
}

private fun unescapeJson(raw: String): String {
    val sb = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
            when (val n = raw[i + 1]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append(0x0C.toChar())
                'u' -> if (i + 5 < raw.length) {
                    sb.append(raw.substring(i + 2, i + 6).toInt(HEX_RADIX).toChar())
                    i += 4
                }
                else -> sb.append(n)
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

/** Escape [s] for embedding in a JSON string literal. */
internal fun jsonEscape(s: String): String {
    val sb = StringBuilder(s.length + 2)
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}

private const val HEX_RADIX = 16
