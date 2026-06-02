package agents_engine.langfuse

import java.time.Instant

internal data class LangfuseIngestionEvent(
    val id: String,
    val type: String,
    val timestamp: Instant,
    val body: Map<String, Any?>,
    val metadata: Map<String, Any?> = emptyMap(),
) {
    fun toWireMap(): Map<String, Any?> =
        linkedMapOf(
            "id" to id,
            "type" to type,
            "timestamp" to timestamp.toString(),
            "metadata" to metadata,
            "body" to body,
        )
}
