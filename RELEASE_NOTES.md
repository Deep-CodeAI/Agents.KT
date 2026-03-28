# Agents.KT v0.1.1 — Tool Error Recovery

**Release date:** 2026-03-29

The fixer is an agent.

## What's new

### Tool Error Recovery System

Every agent framework hits the same wall: tools fail at runtime. Malformed arguments, network errors, flaky APIs, type mismatches. The standard response is a dedicated parser class or a callback function. Agents.KT takes a different position: **the fixer is an `Agent<String, String>`** — same type system, same composition, same telemetry as everything else. Deterministic agents (`implementedBy`) cost zero LLM calls.

#### `onError` inside the tool block

Error handling lives where the tool lives:

```kotlin
tool("calculateNumberOfKeys") {
    description("Count top-level keys in a JSON object")
    executor { args ->
        val json = args["json"]?.toString() ?: throw IllegalArgumentException("Missing json")
        Regex(""""([^"]+)"\s*:""").findAll(json).count()
    }
    onError {
        executionError { _ -> fix(agent = jsonFixer, retries = 2) }
        invalidArgs { _, _ -> fix(agent = jsonFixer) }
    }
}
```

Three placement options with clear priority:
1. **Tool block `onError {}`** — highest priority
2. **Agent-level `onToolError("name") {}`** — middle
3. **`defaults { onError {} }`** — lowest, applies to all tools

#### The fixer is always an agent

No lambda callbacks. Repair uses `Agent<String, String>` — deterministic or LLM-driven:

```kotlin
// Deterministic — zero LLM calls
val jsonFixer = agent<String, String>("json-fixer") {
    skills {
        skill<String, String>("cleanup", "Fix JSON") {
            implementedBy { input -> input.replace(",}", "}").replace(",]", "]") }
        }
    }
}

// LLM-driven — uses a model to analyze and fix
val smartFixer = agent<String, String>("smart-fixer") {
    prompt("Fix malformed JSON. If structural error, call escalate.")
    model { ollama("gpt-4o-mini"); temperature = 0.0 }
    skills {
        skill<String, String>("fix", "Analyze and fix JSON errors") {
            tools("escalate")
        }
    }
}
```

#### Built-in `escalate` and `throwException` tools

Every agent has two framework-provided tools registered at construction time — **inactive by default**, activated when a skill references them in `tools(...)`.

- **`escalate`** — soft failure. The error is fed back to the parent LLM as a tool result, giving it a chance to retry with corrected arguments. The fixer can include corrected data in the escalation reason.
- **`throwException`** — hard failure. `ToolExecutionException` propagates immediately. No retries.

```kotlin
// LLM-driven fixer calls escalate → error fed back → parent LLM retries
LLM calls parseJson(json = "{name: world}")
  → tool throws: "unquoted keys"
  → fixer invoked → fixer calls escalate("Corrected: {\"name\":\"world\"}")
    → error fed back to parent LLM
      → parent retries with corrected JSON → succeeds
```

#### `ToolError` sealed hierarchy

Four error types for programmatic handling:

```kotlin
sealed interface ToolError {
    data class InvalidArgs(val rawArgs: String, val parseError: String, val expectedSchema: Map<String, Any?>)
    data class DeserializationError(val rawValue: String, val targetType: KType, val cause: Throwable)
    data class ExecutionError(val args: Map<String, Any?>, val cause: Throwable)
    data class EscalationError(val source: String, val reason: String, val severity: Severity, val originalError: ToolError, val attempts: Int)
}

enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }
```

### Tool Definition Block DSL

New `ToolDefBuilder` for richer tool definitions:

```kotlin
tools {
    tool("fetch") {
        description("Fetch a URL")
        executor { args -> httpGet(args["url"].toString()) }
        onError {
            executionError { _ -> retry(maxAttempts = 3) }
        }
    }
}
```

All existing `tool("name", "description") { args -> ... }` forms continue to work.

## New files

| File | Purpose |
|------|---------|
| `model/ToolError.kt` | `ToolError` sealed hierarchy, `Severity`, `EscalationException`, `ToolExecutionException` |
| `model/OnErrorBuilder.kt` | `RepairResult`, `RepairScope`, `ToolErrorHandler`, `OnErrorBuilder`, `executeAgentFix` |

## Modified files

| File | Change |
|------|--------|
| `model/ToolDef.kt` | `ToolDefBuilder` block DSL, `ToolDefaultsBuilder`, `buildBuiltInTools()` (escalate/throwException) |
| `model/AgenticLoop.kt` | `executeToolWithRecovery()` — error handler dispatch with retry, agent repair, escalation feedback |
| `core/Agent.kt` | `onToolError()`, `getToolErrorHandler()`, built-in tool auto-registration |

## Tests

**78 new tests** across 10 test files:

| File | Tests | Coverage |
|------|-------|----------|
| `ToolErrorTest` | 6 | Sealed hierarchy construction, exhaustive `when` |
| `OnErrorDSLTest` | 10 | `invalidArgs`, `deserializationError`, `executionError` handlers |
| `ToolErrorDefaultsTest` | 3 | Defaults apply to all tools, per-tool overrides |
| `ToolErrorAgentRepairTest` | 4 | Agent-based fix, retries, escalation, throwException |
| `ToolErrorAgenticLoopTest` | 6 | Retry recovery, retry exhaustion, escalation feedback, defaults in loop |
| `ToolLevelOnErrorTest` | 16 | `onError` via `onError=` param, priority chain, agentic loop, escalation, throwException |
| `ToolBlockOnErrorTest` | 9 | `tool {}` block DSL, priority over defaults/agent-level, agentic loop |
| `EscalateToolTest` | 10 | Built-in tools in every agent, activation via `tools(...)`, severity parsing |
| `JsonParseEscalationIntegrationTest` | 3 | Full escalation flow: malformed JSON → fixer escalates → LLM retries → succeeds |
| `ThrowExceptionIntegrationTest` | 5 | Hard failure: throwException kills pipeline, doesn't fire onToolUse, ignores remaining retries |

**Integration tests** (live LLM via Ollama):
- Flaky tool retry recovery with real LLM
- Retry exhaustion → `ToolExecutionException`
- Escalation → LLM reads corrected data from error → retries → succeeds
- Agent-based repair with real LLM
- Defaults across multiple tools with real LLM
- Tool block `onError` with escalation and real LLM
- `throwException` stops pipeline with real LLM

## Breaking changes

None. All existing APIs and tests continue to work unchanged.

## Upgrade

```kotlin
// build.gradle.kts
dependencies {
    implementation("ai.deep-code:agents-kt:0.1.1")
}
```

---

*Agents.KT — Define Freely. Compose Strictly. Ship Reliably.*
