---
description: Source-file knowledge for agents_engine/core/Agent.kt — the Agent<IN, OUT> class, single-placement rule, invoke / invokeSuspend / session entry points, observability hooks (skillChosenListener, toolUseListener, knowledgeUsedListener, errorListener, budgetThresholdListener), freeze-after-construction contract. Call when the IDE LLM needs to reason about how Agents are constructed, invoked, or observed.
---

# `agents_engine/core/Agent.kt` — the typed-agent class

`Agent<IN, OUT>` is the framework's primary type. One input type, one output type, one job. Type mismatches at composition boundaries are caught by the compiler; structural misuses fail fast at construction time.

## Construction

Agents are built through the `agent { }` DSL — never via direct constructor calls in user code:

```kotlin
val parse = agent<String, Spec>("parse") {
    prompt("Parse the user's request.")
    model { ollama("gpt-oss:120b-cloud") }
    skills {
        skill<String, Spec>("parseRequest", "Splits text into a Spec") {
            implementedBy { input -> Spec(input.split(",").map { it.trim() }) }
        }
    }
}
```

After construction, `validate()` runs internally and the agent is **frozen** — skills, tools, knowledge, and observability hooks are read-only. Mutation attempts after construction throw `IllegalStateException`. The freeze guarantees concurrent-invocation safety.

## Invocation surfaces

Three entry points, all routing through the same skill-resolution + agentic loop:

| Method | Returns | Use when |
|---|---|---|
| `agent.invoke(input)` (alias `agent(input)`) | `OUT` (blocking) | Synchronous call sites |
| `agent.invokeSuspend(input)` | `OUT` (suspending) | Already inside a coroutine |
| `agent.session(input)` | `AgentSession<OUT>` with `events: Flow<AgentEvent<OUT>>` + `await()` | Need to observe tokens, tool calls, or stream UI updates (v0.5.0+) |

All three honor budget caps (`maxTurns`, `maxToolCalls`, `maxDuration`, `perToolTimeout`, `maxTokens`, `maxConsecutiveSameTool`) and the per-skill tool authorization model.

## Single-placement rule

A given `Agent` instance may be wired into **at most one** structure — `then`, `/`, `forum`, `Branch`, `Loop`, `wrap`, etc. Attempting a second placement throws `IllegalArgumentException` at construction time. This is the framework's defense against "the same agent showing up in two places and behaving inconsistently."

## Observability hooks (post-hoc PipelineEvent stream)

Set via the builder:

- `onSkillChosen { name -> ... }` — fires when skill resolution picks a skill
- `onToolUse { name, args, result -> ... }` — fires per tool invocation inside `executeAgentic`
- `onKnowledgeUsed { name, content -> ... }` — fires when a knowledge entry is loaded
- `onError { throwable -> ... }` — fires on any infrastructure error (LLM transport, parse, budget) — original exception always rethrows
- `onBudgetThreshold(threshold) { reason, usedPercent -> ... }` — pre-cap warning hook
- `observe { event: PipelineEvent -> ... }` — sealed-event view that bridges all four hooks into one stream

These are separate from `AgentEvent` (the v0.5.0 streaming session surface) — observability hooks fire post-hoc per skill; AgentEvent fires inside the loop.

## Skill resolution

When `invoke(input)` is called:
1. `resolveSkill(input)` picks a skill whose `inType` matches `input` and whose `outType` matches the agent's `OUT`. Manual override via `skillSelection { input -> "skillName" }`; automatic LLM routing when multiple skills match and no manual selector is set.
2. `skillChosenListener` fires.
3. If the skill is agentic (declared via `tools(...)`), `executeAgentic(this, skill, input)` runs — multi-turn `chat ↔ tools` driven by the LLM.
4. If the skill is non-agentic (declared via `implementedBy { }`), the executor lambda runs directly.

## Internal session entry point

`invokeSuspendForSession(input, emitter, onSkillCompleted, onSkillStarted)` is the streaming-aware variant (`internal`, called only by `Agent.session(input)` and composition operators). It threads an `AgentEventEmitter` through to `executeAgentic` so `Token`/`ToolCall*` events surface in the consumer's `Flow<AgentEvent<OUT>>`. Existing `invokeSuspend` delegates to this with a no-op emitter — byte-for-byte unchanged non-streaming behavior.

## Related files

- `Skill.kt` — the unit of work an Agent dispatches to.
- `Pipeline.kt` / `Branch.kt` / `Loop.kt` / `Parallel.kt` / `Forum.kt` / `Wrap.kt` / `Swarm.kt` — composition operators.
- `AgenticLoop.kt` — the multi-turn LLM-tool dispatch loop.
- `AgentEvent.kt` / `AgentSession.kt` — the v0.5.0 streaming session surface.
