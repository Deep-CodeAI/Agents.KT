# `onBefore*` Interceptors — Design Draft

> **DESIGN DRAFT — NOT YET IMPLEMENTED.** This document captures the proposed `onBefore*` interceptor family ahead of implementation (#1907). The API surface here is the spec the implementation will follow. If you're reading this looking for runnable code, the framework today only ships post-hoc observer hooks (`onSkillChosen`, `onToolUse`, etc.) — see [model-and-tools.md](model-and-tools.md). Track the implementation issue ([#1907](../../issues/1907)) for "available in v0.7.0" status.

## Why

Today the framework's hook surface is **observer-only**. `onSkillChosen`, `onToolUse`, `onKnowledgeUsed`, `onError`, `onBudgetThreshold` all fire post-hoc and cannot veto, mutate, or substitute. `onToolError` is the single exception — it's a recovery DSL, but only for executor *errors*.

Four distinct features each need a hook with veto/mutate semantics:

1. **Per-client tool policy in `McpServer`** (#1902) — deny a tool call based on the calling principal.
2. **Consistent pre-tool policy hooks** — the timeout asymmetry that originally fed this design was fixed directly in #1903; interceptors still provide the right place for custom approval, substitution, and denial policy.
3. **Action confirmation for high-privilege tools** — deny or require approval before a write/exec tool runs.
4. **Prompt-injection detection** — inspect untrusted inputs before they reach the model and deny the turn or substitute a sanitised version.

A single Rails-style `before_*` family with a sealed `Decision` return collapses all four into one primitive. No four separate APIs that almost-but-not-quite do the same thing.

## API

### `Decision<T>`

```kotlin
sealed interface Decision<out T> {
    /** Continue with the original value. */
    object Proceed : Decision<Nothing>

    /** Continue with a mutated value (e.g. enriched args, sanitised messages). */
    data class ProceedWith<T>(val replacement: T) : Decision<T>

    /** Refuse. Surfaced to the model as a tool-error-shaped message (loop continues per existing recovery rules). */
    data class Deny(val reason: String) : Decision<Nothing>

    /** Short-circuit with a synthetic result. Tool/skill is NOT invoked. */
    data class Substitute<T>(val result: T) : Decision<T>
}
```

The variance: `Decision<out T>` lets `Proceed` and `Deny` flow through any typed interceptor without phantom-type gymnastics.

### Registration

Three new methods on `Agent`, mirroring the existing observer-hook registration shape:

```kotlin
class Agent<IN, OUT> {
    fun onBeforeSkill(block: (skillName: String) -> Decision<String>)
    fun onBeforeToolCall(block: (name: String, args: Map<String, Any?>) -> Decision<Map<String, Any?>>)
    fun onBeforeTurn(block: (messages: List<ChatMessage>) -> Decision<List<ChatMessage>>)
}
```

All three are listener-shaped — settable post-freeze, consistent with `onToolUse` / `onSkillChosen` today (see `Agent.kt:164`'s "tracing / instrumentation use cases" note that motivates the post-freeze affordance).

## Chain semantics

When multiple interceptors are registered for the same point:

1. **Registration order is execution order.** First registered fires first.
2. **All interceptors run for observation**, but the **first non-`Proceed` decision is the effective one**. Later interceptors still see the original (unmodified) value for observability, but their decisions don't override.
3. **`ProceedWith(x)` is applied** before later interceptors see `x` — they observe the chain's running mutation.

This matters because it preserves the "additive observability" pattern that `Agent.observe` already uses for `PipelineEvent` consumers. You can wire telemetry + policy in any order without one stomping the other.

## Decision-by-decision behavior

### `onBeforeToolCall`

| Decision | Effect |
|---|---|
| `Proceed` | Tool executor runs with original args. Next interceptor in chain (if any) runs first. |
| `ProceedWith(newArgs)` | Tool executor runs with `newArgs`. Subsequent observers (including `onToolUse`) see the mutated args, not the original. |
| `Deny(reason)` | Tool executor is NOT invoked. A synthetic tool-error message is appended to the conversation: `{"tool": "<name>", "result": {"error": "<reason>", "denied_by_policy": true}}`. The agentic loop continues per existing recovery rules. `onToolError` does NOT fire (that's reserved for *executor* errors). |
| `Substitute(result)` | Tool executor is NOT invoked. `result` is appended to the conversation as if the tool had returned it. Useful for mocked tools in tests AND for "I already know the answer, don't bother calling" optimizations. |

### `onBeforeSkill`

| Decision | Effect |
|---|---|
| `Proceed` | Selected skill runs normally. |
| `ProceedWith(name)` | (Reserved — v1 probably doesn't allow this. See open question 2 below.) |
| `Deny(reason)` | Skill is NOT invoked. Agent throws `SkillDeniedException(reason)`. |
| `Substitute(result)` | Skill is NOT invoked. `result` (typed as `String` — agent OUT path) is returned as the agent's output. |

### `onBeforeTurn`

| Decision | Effect |
|---|---|
| `Proceed` | LLM is called with original messages. |
| `ProceedWith(newMessages)` | LLM is called with `newMessages`. Useful for prompt-injection sanitisation, message redaction, prompt template injection. |
| `Deny(reason)` | LLM is NOT called. The agentic loop terminates with a `TurnDeniedException(reason)`. |
| `Substitute(messages)` | (Reserved — v1 probably doesn't allow this. The LLM's role is to generate; substituting a generated turn is what `ProceedWith` already does at a different level.) |

## Where the loop calls each

In `AgenticLoop.executeAgentic`:

1. **After skill resolution** (`resolveSkill(input)` → `Skill`), BEFORE `onSkillChosen` fires → invoke `onBeforeSkill` chain.
2. **Before each model call** (per turn) → invoke `onBeforeTurn` chain. Both `chat(...)` and `chatStream(...)` paths.
3. **Before each tool dispatch** (after the allowlist check but before the executor runs) → invoke `onBeforeToolCall` chain. Both `executor` and `sessionExecutor` paths — closing the asymmetry that motivated #1903.

The placement of `onBeforeToolCall` AFTER the allowlist matters: it's defense-in-depth, not replacement. The allowlist remains the static guarantee; the interceptor is the dynamic policy layer.

## Worked examples

### Policy denial

```kotlin
agent.onBeforeToolCall { name, args ->
    when {
        name == "writeFile" && (args["path"] as? String)?.startsWith("/etc/") == true ->
            Decision.Deny("system paths are off-limits")
        else -> Decision.Proceed
    }
}
```

The model sees the denial as a tool-error message; the agentic loop typically retries with different args or surrenders. No executor invocation, no side effect.

### Args mutation (trace-ID injection)

```kotlin
agent.onBeforeToolCall { _, args ->
    Decision.ProceedWith(args + ("traceId" to MDC.get("traceId")))
}
```

Tools receive a `traceId` arg even though the LLM never knew about it. The trace context propagates through tool calls into downstream services. Subsequent `onToolUse` observers see the mutated args (so audit logs reflect what the executor actually saw).

### Prompt-injection filter (one-liner)

```kotlin
val filter = PromptInjectionFilter.builtIn()

agent.onBeforeTurn { messages ->
    if (filter.flagged(messages)) Decision.Deny("possible prompt injection — turn rejected")
    else Decision.Proceed
}
```

The injection filter is your choice (Lakera, Rebuff, Anthropic's classifier, a regex). The framework provides the hookpoint.

### Action confirmation pattern

```kotlin
agent.onBeforeToolCall { name, args ->
    if (name !in HIGH_RISK_TOOLS) return@onBeforeToolCall Decision.Proceed
    val approval = approvalService.requestSync(
        toolName = name,
        args = args,
        principal = currentPrincipal(),
        timeout = 30.seconds,
    )
    when (approval) {
        is Approved -> Decision.Proceed
        is Denied -> Decision.Deny("user denied: ${approval.reason}")
        is TimedOut -> Decision.Deny("approval timed out")
    }
}
```

Suspending block — interceptors are `suspend` so they can wait on external approval without blocking the agentic loop's coroutine. (Detail: interceptors execute in the loop's coroutine context; long-running interceptors slow the loop just like any synchronous tool body would.)

### Test mock (Substitute)

```kotlin
// In a test
agent.onBeforeToolCall { name, args ->
    when (name) {
        "fetchAccount" -> Decision.Substitute(mapOf("balance" to 1_000_00, "currency" to "USD"))
        else -> Decision.Proceed
    }
}
```

The test doesn't need to wire a mock `ModelClient` or stub the network — the tool just returns the substituted value as if it had been called.

## Interaction with existing hooks

| Existing hook | Interaction |
|---|---|
| `onSkillChosen` | Fires AFTER `onBeforeSkill` accepts (i.e. after the effective decision is `Proceed` or `ProceedWith`). |
| `onToolUse` | Fires AFTER `onBeforeToolCall` accepts AND after the executor returns. Sees the mutated args (per `ProceedWith`). |
| `onToolError` | Fires only on **executor errors**. NOT on `Deny` (which is policy, not error). |
| `onError` | Fires on `Deny` from `onBeforeSkill` / `onBeforeTurn` IF the resulting exception propagates to the agent boundary. NOT on `Deny` from `onBeforeToolCall` (which is recoverable within the loop). |
| `onBudgetThreshold` | Independent — budget tracking treats `Deny` like a synthetic tool turn (counts toward `maxToolCalls`). |

## Exception safety

Interceptor lambdas execute in the agentic loop's coroutine. If an interceptor throws:

- The exception is caught at the `runInterceptors(...)` boundary.
- The decision is treated as `Deny(reason = ex.message ?: ex.javaClass.simpleName)`.
- The exception is logged via `onError` (the existing infrastructure-error hook).

A buggy interceptor cannot crash the loop or skip the rest of the chain.

## What this replaces

- The per-`ToolDef` `errorHandler` slot stays — it's about executor errors, not policy. Different concern.
- The proposed `toolPolicy` API for `McpServer` (#1902) — `McpServer` will consume `onBeforeToolCall` directly. One mechanism, two consumers.
- Ad-hoc "wrap the tool body to add approval/policy checks" patterns — `onBeforeToolCall` is the canonical shape for veto/mutate/substitute behavior. Built-in `perToolTimeout` already works uniformly on regular and session-aware paths (#1903).

## Open questions

1. **`onBeforeTurn` granularity.** Per turn (entire message list at the start of a turn) vs per outbound model call (the messages going to `client.chat(...)`)? **Proposal: per model call.** That's where injection actually lands; it's also where adapter-level transformations have already happened.
2. **Substitute on `onBeforeSkill`.** Should an interceptor be allowed to force a different skill? **Lean no for v1.** That's what `SkillRoute` is for; we don't want two skill-selection mechanisms competing.
3. **Should `Decision.Deny`'s `reason` field be model-visible vs operator-only?** **Proposal: model-visible** for `onBeforeToolCall.Deny` (the model needs to know why so it can recover), operator-only for `onBeforeSkill.Deny` (no recovery path; just an audit signal).

## Related issues

- **#1907** — this issue (the implementation).
- **#1902** — McpServer hardening; consumes `onBeforeToolCall` for per-client policy.
- **#1903** — `perToolTimeout` enforcement on session path; now implemented directly in `AgenticLoop`.
- **#1908** — ObservabilityBridge; `Decision` events feed the bridge's `onInterceptorDecision` surface.
- **#1918** — three killer 0.6.0 demos; the typed approval demo depends on this.

## Status

| Phase | What it ships |
|---|---|
| Design draft (this doc) | API surface frozen, ready for review |
| **Implementation (#1907)** | `Decision` sealed type + three registration methods + `AgenticLoop` integration + unit tests + worked-examples doc |
| Consumption | `McpServer` (#1902), `perToolTimeout` rewire (#1903), ObservabilityBridge (#1908) |

This doc moves from "DESIGN DRAFT" to "API reference" when the implementation lands.
