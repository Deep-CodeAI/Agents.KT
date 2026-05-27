---
description: Source-file knowledge for agents_engine/model/AgenticLoop.kt — the multi-turn chat↔tool loop (executeAgentic) at the heart of every agentic-skill invocation. Builds per-skill tool allowlist (skill tools + agent capabilities + #856 memory + knowledge), runs turns until final answer or budget cap, applies onBeforeTurn/onBeforeToolCall interceptors (#1907), threads @Generable output JsonSchema to supporting ModelClient providers (#1949), honors maxTurns/maxToolCalls/maxDuration/perToolTimeout/maxTokens/maxConsecutiveSameTool, argument repair up to 8 retries, streaming-aware emitter (#1739), wrap-friendly effectivePrompt (#1707), cumulative TokenUsage (#1740). Call when the IDE LLM needs to reason about how agentic skills actually execute.
---

# `agents_engine/model/AgenticLoop.kt` — the multi-turn `chat ↔ tool` loop

The heart of every agentic-skill invocation. When `Skill.isAgentic == true`, `Agent.invoke` dispatches here.

## Entry point

```kotlin
internal suspend fun <IN> executeAgentic(
    agent: Agent<IN, *>,
    skill: Skill<*, *>,
    input: IN,
    effectivePrompt: String = agent.prompt,  // wrap-friendly override
    emitter: AgentEventEmitter? = null,      // streaming-aware (#1739)
): AgenticResult
```

`AgenticResult(output: Any, tokenUsage: TokenUsage?)` carries the parsed output and cumulative `TokenUsage` summed across all LLM turns of the invocation (#1740). `tokenUsage` is `null` only when no turn reported usage.

## What the loop does

1. **Builds the per-skill tool allowlist** from four sources, deduped by name:
   - `skill.toolNames` (the explicit allowlist on the skill).
   - `agent.autoToolNames` (agent-level capabilities auto-injected for every skill).
   - Per-skill memory tools per #856: present only if `skill.useMemory == true` OR no skill on the agent opts in (legacy fallback) AND `agent.memoryBank != null`.
   - Knowledge tools from `skill.knowledgeTools()` — exposed lazily so the LLM can pull context on demand.

2. **Fail-fast on duplicate tool names** across the allowed sources. Helps catch name collisions between skill tools, agent capabilities, memory, and knowledge.

3. **Runs `chat ↔ tool` turns** via either:
   - `client.chat(messages, jsonSchema)` — non-streaming, when `emitter == null`.
   - `client.chatStream(messages, jsonSchema)` — streaming, when `emitter != null`. Emits `Token` / `ToolCallStarted` / `ToolCallArgumentsDelta` chunks as they arrive.
   - `jsonSchema` is non-null only when the output type is `@Generable`, the skill has no custom `transformOutput { }`, and the client reports `supportsConstrainedDecoding()`.
   - `onBeforeTurn` interceptors run immediately before each outbound model call and may mutate messages, deny the turn, or substitute a final output.

4. **Executes tool calls** by name lookup against the allowlist. Each tool invocation:
   - Runs `onBeforeToolCall` after the allowlist check and before dispatch. `ProceedWith` mutates args, `Deny` feeds a synthetic tool-error message to the model without firing `onToolError` or `onToolUse`, and `Substitute` behaves like a tool result.
   - On `Deny`, fires `agent.toolDeniedListener` with `(name, args, reason)` under the runtime context — surfaces as `PipelineEvent.ToolDenied` so blocked calls stay observable even off the streaming path (#2395). The executor does not run.
   - Honors `perToolTimeout` (regular tools via worker interrupt; session-aware suspend tools via `withTimeout`).
   - Fires `agent.toolUseListener` (post-hoc) with `(name, args, result)` on the executed (non-denied) path.
   - Emits `ToolCallFinished` AgentEvent when streaming.
   - Increments `toolCallCount`, checked against `maxToolCalls` after each call.

5. **Coerces final text into `OUT`** via the skill's `transformOutput { }` OR — if no transformer is set and `OUT` is `@Generable` — via the structured-output decoder in `agents_engine.generation`. Constrained decoding is a first-line provider request; the decoder is still the local trust boundary.

6. **Returns** an `AgenticResult` with the typed output (still as `Any` — the caller casts via the agent's `castOut`) and the cumulative `TokenUsage`.

## Budget enforcement

The loop honors every cap from `BudgetConfig`:

| Cap | Check point | Behavior |
|---|---|---|
| `maxTurns` | After each chat turn | Throws if exceeded. |
| `maxToolCalls` | After each tool execution | Throws if exceeded. |
| `maxDuration` | Before each turn | Throws if `Instant.now() - start > maxDuration`. |
| `perToolTimeout` | Wrapped around each tool call | Throws `BudgetExceededException(PER_TOOL_TIMEOUT)`. Regular tools run on an interruptible worker; session-aware tools use coroutine cancellation. |
| `maxTokens` | Accumulated from `TokenUsage` per turn | Throws when crossed. |
| `maxConsecutiveSameTool` | Per-tool counter reset on tool-name change | Catches LLM stuck in a loop calling the same tool. |

Pre-cap warnings fire via the agent's `budgetThresholdListener(threshold) { reason, usedPercent -> ... }` callback for each cap — wire in your alerting before the hard throw.

## Argument repair

When the LLM produces a tool call whose JSON arguments fail to parse or fail to deserialize into the tool's typed input:

1. The parser error message is reflected back to the LLM as an assistant→tool result.
2. The LLM is asked for corrected arguments.
3. Repeats up to `MAX_ARGUMENT_REPAIR_STEPS` (8) times before giving up.

`MAX_ARGUMENT_REPAIR_STEPS` is a private constant — tunable only by editing this file. The current value handles "LLM keeps fixing the same typo" without exploding turn counts.

## Streaming surface (#1739)

When `emitter != null` (set by `Agent.session(...)`), the loop:
- Calls `client.chatStream(messages, tools)` instead of `chat(...)`.
- Reads `Flow<LlmChunk>` and emits `AgentEvent.Token(text)` per content delta.
- Emits `AgentEvent.ToolCallStarted(name, callId)` on the first chunk of a tool call.
- Emits `AgentEvent.ToolCallArgumentsDelta(callId, deltaJson)` per argument-delta chunk.
- After running the tool executor, emits `AgentEvent.ToolCallFinished(callId, result)`.

The non-streaming path is **unchanged byte-for-byte** when `emitter == null` — `Agent.invoke` / `invokeSuspend` pay zero overhead for the streaming machinery.

## Wrap-friendly effective prompt (#1707)

`effectivePrompt` defaults to `agent.prompt` but can be overridden. The `wrap` operator passes the teacher's output here instead of mutating `agent.prompt` directly — avoids the race where two concurrent invocations of the same wrap pipeline would clobber each other's prompt.

This is a tiny API affordance with a big concurrency payoff: agents stay frozen and concurrent-safe even under wrap composition.

## Where the LLM is called

`agent.modelConfig.client` provides the `ModelClient` — see `model/ModelClient.kt` for the interface and `OllamaClient.kt` / `ClaudeClient.kt` / `OpenAiClient.kt` / `DeepSeekClient.kt` for the shipped implementations. The loop is **provider-agnostic** — it never talks to a specific provider's API directly; only through `chat` / `chatStream`.

## Related files

- `Agent.kt` — calls `executeAgentic` for agentic skills, runs `skill.implementation` directly for deterministic ones.
- `ModelClient.kt` — the LLM transport interface.
- `BudgetConfig.kt` — the cap configuration the loop honors.
- `OnErrorBuilder.kt` — the `onError { }` recovery hook (per-tool retry/skip).
- `Tool.kt` / `ToolDef.kt` — the tool shape executed inside the loop.
- `runtime/events/AgentEvent.kt` — the streaming event types emitted via `emitter`.
- `generation/Generable.kt`, `generation/LenientJsonParser.kt` — the structured-output decoder used for typed `OUT` coercion.
