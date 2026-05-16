---
description: Source-file knowledge for agents_engine/model/BudgetConfig.kt — six caps (maxTurns 8, maxToolCalls 32, maxDuration 5m, perToolTimeout null, maxTokens null #963, maxConsecutiveSameTool null #969), the BudgetBuilder DSL, BudgetReason enum, BudgetExceededException, and pre-cap threshold warnings via onBudgetThreshold. Call when the IDE LLM needs to reason about cost/runaway control for agentic invocations.
---

# `agents_engine/model/BudgetConfig.kt` — agentic invocation caps

Six budget caps, plus a `BudgetBuilder` for the DSL and a `BudgetExceededException(message, reason: BudgetReason)` that fires when any cap trips.

## The caps

| Field | Default | Purpose |
|---|---|---|
| `maxTurns` | `8` | Hard cap on agentic-loop iterations. Most well-designed loops finish in 3–6. |
| `maxToolCalls` | `32` | Cap on TOTAL tool invocations across all turns. Catches a single turn emitting many tool calls. |
| `maxDuration` | `5.minutes` | Wall-clock cap from invocation start. |
| `perToolTimeout` | `null` | Per-tool wall-clock cap. Null = no per-tool cap. |
| `maxTokens` | `null` | Cumulative LLM tokens (prompt + completion). Only accumulates when provider reports `TokenUsage` (#963). |
| `maxConsecutiveSameTool` | `null` | How many times the same tool may be invoked in immediate succession. Catches LLM stuck retrying a broken call (#969). |

All caps are `null` to opt out (no cap). `maxTurns`, `maxToolCalls`, `maxDuration` always apply because their defaults are non-null.

## DSL

```kotlin
agent<X, Y>("...") {
    budget {
        maxTurns = 16
        maxToolCalls = 64
        maxDuration = 2.minutes
        perToolTimeout = 30.seconds
        maxTokens = 100_000
        maxConsecutiveSameTool = 3
    }
}
```

`BudgetBuilder` mirrors `BudgetConfig` field-for-field. `build()` is internal — the agent DSL calls it.

## Failure mode

When a cap is exceeded, the agentic loop throws:

```kotlin
class BudgetExceededException(message: String, val reason: BudgetReason) : RuntimeException
```

`reason` is one of:
- `TURNS` — `maxTurns` exceeded
- `TOOL_CALLS` — `maxToolCalls` exceeded
- `DURATION` — `maxDuration` exceeded
- `PER_TOOL_TIMEOUT` — `perToolTimeout` for a single tool exceeded
- `TOKENS` — `maxTokens` exceeded
- `CONSECUTIVE_TOOL` — `maxConsecutiveSameTool` exceeded

Pattern-match `reason` in your error handler to decide whether to retry, abort, or escalate. The agent's `onError { }` listener fires AND the exception rethrows by default.

## Pre-cap threshold warnings

Wire `onBudgetThreshold(threshold) { reason, usedPercent -> ... }` on the agent to fire BEFORE the cap is hit:

```kotlin
agent<X, Y>("...") {
    budget { maxTurns = 16 }
    onBudgetThreshold(0.75) { reason, used ->
        log.warn("budget $reason at ${used.toInt()}% — may exceed soon")
    }
}
```

Threshold is a fraction in `[0.0, 1.0]`. The listener fires once per cap per invocation at the first turn/call where `usedPercent >= threshold`. Use for alerting / metrics before the hard throw.

## Sizing guidance

- `maxTurns: 8` is a good default for skills that "think and refine." Math, code review, plan-then-execute usually fit.
- `maxToolCalls: 32` accommodates skills that explore (search, branch, read multiple files). Drop to 8–16 for skills with 1–2 expected tool calls.
- `maxDuration: 5.minutes` is generous — drop to 30–60 seconds for skills serving interactive UIs.
- `perToolTimeout` is the right knob for capping a misbehaving external tool (HTTP, DB) — independent of the global budget.
- `maxTokens` is the cost knob — use when you have a per-invocation cost budget.
- `maxConsecutiveSameTool: 3` catches the "LLM loops calling the same tool with the same broken args" pathology that otherwise consumes `maxToolCalls` quietly.

## Related files

- `AgenticLoop.kt` — where every cap is checked.
- `Agent.kt` — the `budget { }` DSL slot and the `onBudgetThreshold` listener.
- `OnErrorBuilder.kt` — `onError { }` recovery hook (fires when `BudgetExceededException` propagates).
