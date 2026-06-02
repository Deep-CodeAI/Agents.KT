package agents_engine.model

import agents_engine.generation.LenientJsonParser

internal data class ParsedToolArguments(
    val arguments: Map<String, Any?>,
    val rawArguments: String? = null,
    val parseError: String? = null,
)

internal fun parseToolArguments(rawArgs: Any?): ParsedToolArguments = when (rawArgs) {
    null -> ParsedToolArguments(emptyMap())
    is Map<*, *> -> ParsedToolArguments(
        arguments = rawArgs.entries.associate { (k, v) -> k.toString() to v },
    )
    is String -> {
        val trimmed = rawArgs.trim()
        if (trimmed.isEmpty()) {
            ParsedToolArguments(emptyMap(), rawArguments = rawArgs)
        } else {
            val parsed = LenientJsonParser.parse(rawArgs)
            when (parsed) {
                is Map<*, *> -> ParsedToolArguments(
                    arguments = parsed.entries.associate { (k, v) -> k.toString() to v },
                    rawArguments = rawArgs,
                )
                null -> ParsedToolArguments(
                    arguments = emptyMap(),
                    rawArguments = rawArgs,
                    parseError = "Could not parse tool arguments as JSON object.",
                )
                else -> ParsedToolArguments(
                    arguments = emptyMap(),
                    rawArguments = rawArgs,
                    parseError = "Tool arguments must decode to a JSON object.",
                )
            }
        }
    }
    else -> ParsedToolArguments(
        arguments = emptyMap(),
        rawArguments = rawArgs.toString(),
        parseError = "Tool arguments must be provided as a JSON object.",
    )
}
