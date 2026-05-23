# `onBefore*` Interceptors

Agents.KT ships a before-interceptor family for dynamic policy, mutation, and substitution decisions before skills, model turns, and tool calls run (#1907). These hooks complement post-hoc observers such as `onSkillChosen`, `onToolUse`, and `Agent.observe { }`.

## API

```kotlin
sealed interface Decision<out T> {
    object Proceed : Decision<Nothing>
    data class ProceedWith<T>(val replacement: T) : Decision<T>
    data class Deny(val reason: String) : Decision<Nothing>
    data class Substitute<out R>(val result: R) : Decision<Nothing>
}

agent.onBeforeSkill { skillName -> Decision.Proceed }
agent.onBeforeTurn { messages -> Decision.ProceedWith(messages) }
agent.onBeforeToolCall { name, args -> Decision.Proceed }
```

`ProceedWith` replaces the inspected value: skill name, outbound messages, or tool args. `Substitute` short-circuits with a synthetic result, so `Decision.Substitute("cached")` can be returned from any interceptor type.

## Chain Semantics

When multiple interceptors are registered for the same point:

1. Registration order is execution order.
2. All interceptors run for observation.
3. The first non-`Proceed` decision is the effective one; later decisions do not override it.
4. `ProceedWith(x)` updates the running value before later interceptors observe it.
5. Thrown interceptor exceptions are converted to `Decision.Deny(ex.message ?: ex.toString())`.

## Decision Behavior

### `onBeforeSkill`

Runs after skill resolution and before `onSkillChosen`.

| Decision | Effect |
|---|---|
| `Proceed` | Selected skill runs normally. |
| `ProceedWith(name)` | Reroutes to another compatible skill by name. |
| `Deny(reason)` | Throws `InterceptorDeniedException`; `onError` observes it if it escapes. |
| `Substitute(result)` | Skips the skill and returns `result` through the agent's `OUT` cast path. |

### `onBeforeTurn`

Runs before each outbound model call in both `chat` and `chatStream` paths.

| Decision | Effect |
|---|---|
| `Proceed` | Model sees the current messages. |
| `ProceedWith(messages)` | Model sees the replacement messages. |
| `Deny(reason)` | Throws `InterceptorDeniedException`; the model is not called. |
| `Substitute(result)` | Skips the model call and returns `result` as the final output. |

### `onBeforeToolCall`

Runs after the static per-skill allowlist check and before dispatch. It covers regular `executor`, session-aware `sessionExecutor`, and incoming `McpServer` `tools/call` requests for exposed skills.

| Decision | Effect |
|---|---|
| `Proceed` | Tool runs with original args. |
| `ProceedWith(args)` | Tool runs with replacement args; `onToolUse` and session events see those args. |
| `Deny(reason)` | Tool does not run. The model receives `ERROR: Tool '<name>' denied by policy: <reason>`. `onToolError` does not fire. |
| `Substitute(result)` | Tool does not run. `result` is treated as the tool result and is visible to `onToolUse`, tool messages, and session events. |

## Examples

### Policy Denial

```kotlin
agent.onBeforeToolCall { name, args ->
    when {
        name == "writeFile" && (args["path"] as? String)?.startsWith("/etc/") == true ->
            Decision.Deny("system paths are off-limits")
        else -> Decision.Proceed
    }
}
```

The executor is not invoked, and the model can recover from the synthetic tool-error message.

### Args Mutation

```kotlin
agent.onBeforeToolCall { _, args ->
    Decision.ProceedWith(args + ("traceId" to currentTraceId()))
}
```

The executor and `onToolUse` observer see the same mutated args.

### Prompt-Injection Filter

```kotlin
agent.onBeforeTurn { messages ->
    if (filter.flagged(messages)) Decision.Deny("possible prompt injection")
    else Decision.Proceed
}
```

Agents.KT provides the hook; you choose the detector.

### Synthetic Test Result

```kotlin
agent.onBeforeToolCall { name, _ ->
    when (name) {
        "fetchAccount" -> Decision.Substitute(mapOf("balance" to 100_00, "currency" to "USD"))
        else -> Decision.Proceed
    }
}
```

Useful for tests and cache hits where the tool body should not run.

## Existing Hook Interactions

| Existing hook | Interaction |
|---|---|
| `onSkillChosen` | Fires after `onBeforeSkill` accepts or reroutes. |
| `onToolUse` | Fires after accepted/substituted tool calls; sees mutated args. |
| `onToolError` | Fires only on executor errors, not policy `Deny`. |
| `onError` | Fires for `onBeforeSkill` / `onBeforeTurn` denials that escape the agent boundary. |
| `onBudgetThreshold` | Independent. Denied tool calls still count as model-requested tool calls. |

## Related Issues

- #1907 — implementation.
- #1902 — McpServer hardening; builds on the same `onBeforeToolCall` hook for per-client policy.
- #1908 — ObservabilityBridge; interceptor decisions are future bridge inputs.
- #1918 — demos; typed approval flows build on this primitive.
