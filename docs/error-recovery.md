[← Back to README](../README.md)

## Tool Error Recovery

Tools fail at runtime — network errors, timeouts, flaky APIs. Agents.KT lets each tool declare its own recovery strategy, and **the fixer is always an agent**. No special parser class, no lambda callbacks — a regular `Agent<String, String>` with the same composition, telemetry, and budget tracking as everything else. Deterministic agents (`implementedBy`) cost zero LLM calls.

### `onError` inside the tool block

Error handling lives where the tool lives:

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
// Tool throws → retries up to 3 times → succeeds or throws ToolExecutionException
```

Agent-based repair — the fixer is an `Agent<String, String>`:

```kotlin
val jsonFixer = agent<String, String>("json-fixer") {
    skills {
        skill<String, String>("cleanup", "Fixes common JSON issues") {
            implementedBy { input -> input.replace(",}", "}").replace(",]", "]") }
        }
    }
}

tools {
    tool("parse") {
        description("Parse JSON input")
        executor { args -> parseJson(args["json"].toString()) }
        onError {
            invalidArgs { _, _ -> fix(agent = jsonFixer) }
            executionError { _ -> fix(agent = jsonFixer, retries = 3) }
        }
    }
}
```

Shorthand form also works — `onError` as a named parameter:

```kotlin
tools {
    tool("fetch", "Fetch a URL", onError = {
        executionError { _ -> retry(maxAttempts = 3) }
    }) { args -> httpGet(args["url"].toString()) }
}
```

Or at the agent level via `onToolError`:

```kotlin
onToolError("fetch") {
    executionError { _ -> retry(maxAttempts = 3) }
}
```

### Defaults and priority

Set defaults once, override per tool:

```kotlin
tools {
    defaults {
        onError {
            executionError { _ -> retry(maxAttempts = 3) }
        }
    }
    tool("fetch", "Fetch URL") { _ -> httpGet() }      // inherits retry(3)
    tool("compile") {
        description("Compile code")
        executor { _ -> compile() }
        onError { executionError { _ -> retry(maxAttempts = 1) } }  // overrides
    }
}
```

Resolution priority: **tool block `onError`** > **agent-level `onToolError`** > **defaults**.

### Built-in tools: `escalate` and `throwException`

Every agent has two framework-provided tools — `escalate` and `throwException`. They exist in every agent's `toolMap` but are **inactive by default**. A skill activates them by referencing them in `tools(...)`:

```kotlin
val fixer = agent<String, String>("json-fixer") {
    prompt("Fix malformed JSON. If structural error, call escalate. If binary garbage, call throwException.")
    model { ollama("gpt-4o-mini"); temperature = 0.0 }
    skills {
        skill<String, String>("fix", "Fix JSON") {
            tools("escalate", "throwException")   // activates built-in tools
        }
    }
}
```

**`escalate`** — soft failure. The error is fed back to the parent LLM as a tool result, giving it a chance to retry with corrected arguments:

```
LLM calls parseJson(json = "{name: world}")  →  tool throws (unquoted keys)
  → fixer agent tries to fix
  → fixer LLM calls escalate(reason = "Unquoted keys. Corrected: {\"name\":\"world\"}")
    → error fed back to parent LLM: "ERROR: Tool 'parseJson' failed: Unquoted keys..."
      → parent LLM retries: parseJson(json = '{"name":"world"}')  →  succeeds
```

**`throwException`** — hard failure. `ToolExecutionException` propagates immediately through the pipeline. No retries.

Deterministic agents can also escalate by throwing directly:

```kotlin
implementedBy { _ ->
    throw EscalationException("Schema mismatch", Severity.HIGH)  // soft
    // or
    throw ToolExecutionException("Binary data, not JSON")         // hard
}
```

### Full example: JSON key counter with escalation recovery

A complete working example — agent parses malformed JSON via a tool, fixer agent escalates with corrected data, the LLM retries and succeeds:

```kotlin
// Fixer agent: LLM-driven, uses the built-in escalate tool.
// Analyzes the parse error and suggests corrected JSON in the escalation reason.
val fixer = agent<String, String>("json-fixer") {
    prompt(
        "You receive a string that failed to parse as JSON. " +
        "Call the escalate tool with a reason that includes the corrected valid JSON."
    )
    model { ollama("gpt-4o-mini"); temperature = 0.0 }
    budget { maxTurns = 3 }
    skills {
        skill<String, String>("fix", "Analyze and escalate JSON errors") {
            tools("escalate")   // activates the built-in escalate tool
        }
    }
}

// Main agent: uses calculateNumberOfKeys tool with onError inside the tool block.
val agent = agent<String, String>("json-analyst") {
    prompt(
        "Use the calculateNumberOfKeys tool to count keys in JSON objects. " +
        "If a tool returns an ERROR, read it carefully — it contains corrected JSON. " +
        "Retry the tool with the corrected JSON. Reply with ONLY the number."
    )
    model { ollama("llama3"); temperature = 0.0 }
    budget { maxTurns = 10 }
    tools {
        tool("calculateNumberOfKeys") {
            description("Count top-level keys in a JSON object. Args: json (valid JSON string)")
            executor { args ->
                val json = args["json"]?.toString()
                    ?: throw IllegalArgumentException("Missing 'json' argument")
                val keys = Regex(""""([^"]+)"\s*:""").findAll(json).toList()
                if (keys.isEmpty()) throw IllegalArgumentException("No valid keys — unquoted keys?")
                keys.size
            }
            onError {
                executionError { _ -> fix(agent = fixer, retries = 2) }
            }
        }
    }
    skills {
        skill<String, String>("solve", "Analyze JSON using tools") {
            tools("calculateNumberOfKeys")
        }
    }
    onToolUse { name, args, result ->
        println("  $name(${args["json"].toString().take(60)}) = $result")
    }
}

agent("How many keys? {name: world, age: 30, active: true}")
//   calculateNumberOfKeys({name: world, age: 30, active: true}) =
//       ERROR: Tool 'calculateNumberOfKeys' failed: No valid keys — unquoted keys? ...
//   calculateNumberOfKeys({"name":"world","age":30,"active":true}) = 3
// → "3"
```

The flow:
1. LLM calls `calculateNumberOfKeys(json="{name: world, ...}")` — malformed, unquoted keys
2. Tool throws → `onError` invokes `fixer` agent
3. Fixer LLM analyzes the error, calls `escalate(reason="...corrected: {\"name\":\"world\",...}")`
4. Escalation error fed back to main LLM as tool result
5. Main LLM reads the corrected JSON from the error, retries with valid JSON
6. Tool succeeds → returns `3`

### Error types

`ToolError` is a sealed hierarchy for programmatic handling:

```kotlin
sealed interface ToolError {
    data class InvalidArgs(val rawArgs: String, val parseError: String, ...)
    data class DeserializationError(val rawValue: String, val targetType: KType, ...)
    data class ExecutionError(val args: Map<String, Any?>, val cause: Throwable)
    data class EscalationError(val source: String, val reason: String, val severity: Severity, ...)
}
// Severity: LOW, MEDIUM, HIGH, CRITICAL
```

No tool has error handling by default. When no handler is set and a tool throws, the exception propagates normally — zero overhead on the happy path.

## Model-call error recovery — `onLLMError`

Tool recovery (above) handles a tool that fails; `onLLMError` (#3508) handles the **model call itself** failing — a down provider (raw `ConnectException`), a 5xx, a malformed response. The default with no handler is fail fast and loud: the original exception propagates, identity preserved. A registered handler returns an `LlmErrorDecision` per failed attempt:

```kotlin
agent<String, Report>("analyst") {
    model { openai("gpt-5") }
    onLLMError { e ->
        when {
            e is ConnectException -> LlmErrorDecision.Retry(maxAttempts = 3, initialBackoffMillis = 500)
            else                  -> LlmErrorDecision.RespondWith(Report.empty())
        }
    }
}
```

- **`Rethrow`** (the default) — fail fast and loud; the original exception propagates.
- **`RespondWith(output)`** — recover with a typed fallback; the value must be assignable to the agent's `OUT` (a wrong type fails fast with `ClassCastException`).
- **`Retry(maxAttempts, initialBackoffMillis)`** (#4495) — re-run the call with exponential backoff (500ms → 1s → 2s …, by default). `maxAttempts` counts the original call; the handler is consulted again on every failure, so it can switch to `RespondWith`/`Rethrow` mid-schedule. The attempt budget is **per model turn** — one flaky turn can't starve a multi-turn run. Exhaustion rethrows the original error, exactly as `Rethrow` would.

The handler does **not** fire for budget caps (`onBudgetExceeded` owns those) or cancellation, and v1 scopes recovery to the agentic loop — a model failure during multi-skill LLM routing still propagates loud. See [production-hardening.md](production-hardening.md) for the deployment checklist entry.

---
