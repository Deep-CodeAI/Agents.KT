package agents_engine.model

/**
 * Provider-neutral structured-output schema request. [schema] is a JSON Schema
 * object encoded as JSON text; adapters embed it in their provider-specific
 * field (`response_format`, `format`, tool-shaped schema, etc.).
 */
data class JsonSchema(
    val name: String,
    val schema: String,
)

internal fun JsonSchema.wireName(): String =
    name
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .trim('_')
        .ifBlank { "structured_output" }
        .take(64)
