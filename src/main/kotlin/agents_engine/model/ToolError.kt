package agents_engine.model

import kotlin.reflect.KType

enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }

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

class EscalationException(val reason: String, val severity: Severity) : RuntimeException(reason)

class ToolExecutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
