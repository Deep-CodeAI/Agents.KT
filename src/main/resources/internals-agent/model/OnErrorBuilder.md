# `agents_engine/model/OnErrorBuilder.kt` — the `onError { }` recovery DSL

Lets the user intercept tool failures and either repair them, retry them, escalate, or surrender.

## Failure points

Three places in the agentic loop call into the handler:

| Where | Slot | Signature |
|---|---|---|
| LLM produced unparseable tool args | `invalidArgs` | `(rawArgs, parseError) -> RepairResult?` |
| Args parsed but failed to deserialize into typed `Args` | `deserializationError` | `(rawValue, error) -> RepairResult?` |
| Tool executor threw | `executionError` | `(cause: Throwable) -> RepairResult?` |

Each returns nullable — `null` falls through to `Unrecoverable`.

## RepairResult variants

```kotlin
sealed interface RepairResult {
    data class Fixed(val value: String)                                 // here's the corrected output, use it
    data class Retry(val maxAttempts: Int)                              // retry the original up to N times
    data class Escalated(val reason: String, val severity: Severity)    // escalate to a human/external system
    data object Unrecoverable                                           // give up
}
```

## DSL

```kotlin
tool<MyArgs, String>("doThing") { args -> realLogic(args) } onError {
    invalidArgs { raw, err ->
        // Hand the broken JSON to a sibling agent that repairs it.
        fix(jsonFixerAgent, retries = 2)
    }
    deserializationError { raw, err -> retry(maxAttempts = 3) }
    executionError { cause ->
        if (cause is TimeoutException) retry(maxAttempts = 1)
        else RepairResult.Escalated("network down", Severity.HIGH)
    }
}
```

`RepairScope.fix(agent, retries)` is the canonical pattern: hand the bad input to a sibling string→string agent that produces corrected output. Useful for "this JSON is broken — fix it" repairs without the main agent having to know how.

## Internals

- `OnErrorBuilder.build()` returns a `ToolErrorHandler` carrying the three slots.
- `executeAgentFix(agent, input, retries)` is the helper backing `fix(agent)`: invokes the agent up to `retries` times, returns `Fixed(result)` on success, `Escalated(...)` if the repair agent threw `EscalationException`, `Unrecoverable` if all retries exhausted.
- `ToolExecutionException` propagates out (used to signal the repair itself failed catastrophically — the loop doesn't try to repair the repair).

## Related files

- `ToolError.kt` — the `Severity` enum, `EscalationException`, `ToolExecutionException`.
- `ToolDef.kt` — `errorHandler` slot wired by the typed `tool(...) onError { }` infix.
- `AgenticLoop.kt` — calls `handleInvalidArgs` / `handleDeserializationError` / `handleExecutionError` at the three failure points.
