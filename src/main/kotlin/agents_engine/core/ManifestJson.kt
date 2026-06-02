package agents_engine.core

import agents_engine.internal.toJsonString

internal object ManifestJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        is String -> quote(value)
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, mapValue) ->
            "${quote(key.toString())}:${encode(mapValue)}"
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> quote(value.toString())
    }
    // #2799 — routes through the central [toJsonString] escaper. The local
    // body was byte-identical to JsonEscape — parallel impls are drift hazard.
    private fun quote(value: String): String = value.toJsonString()
}
