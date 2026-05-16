# `agents_engine/model/ToolError.kt` — typed tool-failure union

The four failure shapes plus the `Severity` enum and the two helper exceptions.

## Severity

```kotlin
enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }
```

Used in `EscalationException` and `ToolError.EscalationError` to express how loud an escalation should be (UI hint, alert routing, etc.).

## `ToolError` variants

```kotlin
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
```

| Variant | When |
|---|---|
| `InvalidArgs` | LLM emitted unparseable args JSON. `expectedSchema` lets the repair path tell the LLM the right shape. |
| `DeserializationError` | Args parsed as a Map but failed to coerce into the typed `Args` (missing field, wrong type). |
| `ExecutionError` | The tool body threw. |
| `EscalationError` | A previous repair attempt invoked `escalate(reason, severity)` — wraps the original error for context. |

## Exceptions

```kotlin
class EscalationException(val reason: String, val severity: Severity) : RuntimeException(reason)
class ToolExecutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

- `EscalationException` is what `RepairScope.escalate(reason, severity)` throws and what triggers a switch from `RepairResult.Retry` / `Fixed` to `RepairResult.Escalated`.
- `ToolExecutionException` is what the agentic loop wraps tool-body throws into when re-raising past the repair handler.

## Related files

- `OnErrorBuilder.kt` — the DSL that consumes these via `invalidArgs { }`, `deserializationError { }`, `executionError { }`.
- `AgenticLoop.kt` — the source of `InvalidArgs` / `DeserializationError` / `ExecutionError` events.
- `ToolDef.kt` — `errorHandler` slot wired to a `ToolErrorHandler` derived from these.
