package agents_engine.model

import kotlin.reflect.KType

/**
 * `agents_engine/model/ToolError.kt` — the typed error union for tool
 * failures ([ToolError.InvalidArgs], [DeserializationError],
 * [ExecutionError], [EscalationError]), the [Severity] enum, and the
 * companion exceptions ([EscalationException], [ToolExecutionException]).
 * Consumed by the `onError { }` repair DSL in [OnErrorBuilder]. See
 * `src/main/resources/internals-agent/model/ToolError.md` (#1837 / #1858).
 */

sealed interface ToolError {
    data class InvalidArgs(
        val rawArgs: String,
        val parseError: String,
        val expectedSchema: Map<String, Any?>,
    ) : ToolError

    data class DeserializationError(
        val rawValue: String,
        val targetType: KType,
        val cause: Throwable,
    ) : ToolError

    data class ExecutionError(
        val args: Map<String, Any?>,
        val cause: Throwable,
    ) : ToolError

    data class EscalationError(
        val source: String,
        val reason: String,
        val severity: Severity,
        val originalError: ToolError,
        val attempts: Int,
    ) : ToolError
}
