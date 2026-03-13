package agents_engine.model

import agents_engine.generation.LenientJsonParser

object InlineToolCallParser {
    fun parse(content: String): ToolCall? {
        val parsed = LenientJsonParser.parse(content.trim()) as? Map<*, *> ?: return null
        val name = parsed["tool"] as? String ?: return null
        val rawArgs = parsed["arguments"] as? Map<*, *> ?: emptyMap<String, Any?>()
        return ToolCall(
            name = name,
            arguments = rawArgs.entries.associate { (k, v) -> k.toString() to v },
        )
    }

    fun toJson(call: ToolCall): String =
        """{"tool":"${call.name}","arguments":${argsToJson(call.arguments)}}"""

    fun argsToJson(args: Map<String, Any?>): String {
        val entries = args.entries.joinToString(",") { (k, v) -> "\"$k\":${valueToJson(v)}" }
        return "{$entries}"
    }

    private fun valueToJson(v: Any?): String = when (v) {
        null       -> "null"
        is String  -> "\"${v.replace("\"", "\\\"")}\""
        is Number  -> v.toString()
        is Boolean -> v.toString()
        else       -> "\"$v\""
    }
}
