package agents_engine.manifest

internal object StableYaml {
    fun encode(value: Any?): String = buildString {
        appendMap(value as? Map<*, *> ?: emptyMap<Any?, Any?>(), 0)
    }.trimEnd()

    private fun StringBuilder.appendMap(map: Map<*, *>, indent: Int) {
        map.entries.sortedBy { it.key.toString() }.forEach { (key, value) ->
            append(" ".repeat(indent))
            append(key.toString())
            when (value) {
                is Map<*, *> -> {
                    if (value.isEmpty()) {
                        appendLine(": {}")
                    } else {
                        appendLine(":")
                        appendMap(value, indent + 2)
                    }
                }
                is Iterable<*> -> appendList(key = null, value = value.toList(), indent = indent)
                is Array<*> -> appendList(key = null, value = value.toList(), indent = indent)
                else -> appendLine(": ${scalar(value)}")
            }
        }
    }

    private fun StringBuilder.appendList(key: String?, value: List<*>, indent: Int) {
        if (key != null) {
            append(" ".repeat(indent))
            append(key)
        }
        if (value.isEmpty()) {
            appendLine(": []")
            return
        }
        appendLine(":")
        value.forEach { item ->
            append(" ".repeat(indent + 2))
            append("-")
            when (item) {
                is Map<*, *> -> {
                    appendLine()
                    appendMap(item, indent + 4)
                }
                is Iterable<*> -> appendList(key = null, value = item.toList(), indent = indent + 2)
                else -> appendLine(" ${scalar(item)}")
            }
        }
    }

    private fun scalar(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        else -> quote(value.toString())
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
