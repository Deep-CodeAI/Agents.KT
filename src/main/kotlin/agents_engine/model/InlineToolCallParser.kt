package agents_engine.model

import agents_engine.internal.toJsonString

import agents_engine.generation.LenientJsonParser

/**
 * `agents_engine/model/InlineToolCallParser.kt` — parses an LLM's
 * `{"tool":"name","arguments":{...}}` text into a [ToolCall], and the
 * reverse JSON encoder. Used by providers (and the structured-output path)
 * that don't expose native function-calling and instead instruct the LLM
 * to emit inline tool-call JSON. See
 * `src/main/resources/internals-agent/model/InlineToolCallParser.md`
 * for the adjunct surfaced to IDE-side LLM tools (#1837 / #1847).
 */
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
        """{"tool":${call.name.toJsonString()},"arguments":${argsToJson(call.arguments)}}"""

    fun argsToJson(args: Map<String, Any?>): String {
        val entries = args.entries.joinToString(",") { (k, v) -> "${k.toJsonString()}:${valueToJson(v)}" }
        return "{$entries}"
    }

    private fun valueToJson(v: Any?): String = when (v) {
        null       -> "null"
        is String  -> v.toJsonString()
        is Number  -> v.toString()
        is Boolean -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, value) ->
            "${k.toString().toJsonString()}:${valueToJson(value)}"
        }
        is List<*> -> v.joinToString(",", "[", "]") { valueToJson(it) }
        else       -> v.toString().toJsonString()
    }
}

