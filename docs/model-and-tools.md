[← Back to README](../README.md)

## Model & Tool Calling

Attach a model to an agent and mark a skill as agentic with `tools(...)`. The framework runs a multi-turn loop — model calls tools, results flow back, model produces the final answer.

```kotlin
val calculator = agent<String, String>("calculator") {
    prompt("You are a calculator. Use the provided tools to evaluate expressions step by step.")
    model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }

    lateinit var add: Tool<Map<String, Any?>, Any?>
    lateinit var subtract: Tool<Map<String, Any?>, Any?>
    lateinit var multiply: Tool<Map<String, Any?>, Any?>
    lateinit var divide: Tool<Map<String, Any?>, Any?>
    lateinit var power: Tool<Map<String, Any?>, Any?>
    tools {
        add      = tool("add",      "Add two numbers. Args: a, b")             { args -> num(args, "a") + num(args, "b") }
        subtract = tool("subtract", "Subtract b from a. Args: a, b")           { args -> num(args, "a") - num(args, "b") }
        multiply = tool("multiply", "Multiply two numbers. Args: a, b")        { args -> num(args, "a") * num(args, "b") }
        divide   = tool("divide",   "Divide a by b. Args: a, b")               { args -> num(args, "a") / num(args, "b") }
        power    = tool("power",    "Raise base to exponent. Args: base, exp") { args -> Math.pow(num(args, "base"), num(args, "exp")) }
    }

    skills {
        skill<String, String>("solve", "Evaluate arithmetic expressions using tools") {
            tools(add, subtract, multiply, divide, power)
        }
    }

    onToolUse { name, args, result ->
        println("  $name(${args.values.joinToString(", ")}) = $result")
    }
}

calculator("Calculate ((15 + 35) / 2)^2")
//   add(15.0, 35.0) = 50.0
//   divide(50.0, 2.0) = 25.0
//   power(25.0, 2.0) = 625.0
// → "The result is 625."
```

**`model { }`** — configures the LLM backend. Two providers ship today:

- `model { ollama("gpt-oss:120b-cloud"); host = "..."; port = 11434; temperature = 0.0 }` — local or cloud Ollama; auto-fallback to inline JSON tool-call format for models without native tool support (#706).
- `model { claude("claude-opus-4-7"); apiKey = System.getenv("ANTHROPIC_API_KEY"); temperature = 0.0; maxTokens = 4096 }` — Anthropic Messages API; maps the framework's `LlmMessage` / `LlmResponse` model to Anthropic's structured `tool_use` / `tool_result` content blocks; tools advertise as `input_schema` (Anthropic's spelling) (#1644).

Both adapters share the `ModelClient` interface — switching providers is a one-line DSL change. The injectable `client = ...` escape hatch is still there for test stubs or custom adapters (e.g., OpenAI/Google ahead of native support).

**`tools { tool(name, description) { args -> } }`** — registers callable tools. Each tool receives a `Map<String, Any?>` of arguments and returns any value.

**`tools { tool<Args, Result>(name, description) { args -> } }`** — typed variant. `Args` must be `@Generable`; the framework deserializes the model's arguments into a typed instance via reflection (`KClass.constructFromMap`) before invoking the executor. The provider envelope advertises a real JSON Schema generated from `Args::class.jsonSchema()` (proper `properties`, `required`, `@Guide` descriptions per field) instead of the legacy `properties: {}, additionalProperties: true`. Deserialization failures (missing required field, wrong type) route through `onError { invalidArgs { ... } }` like JSON-parse failures, not `executionError`.

```kotlin
@Generable("Write a file to disk")
data class WriteFileArgs(
    @Guide("Absolute path") val path: String,
    @Guide("UTF-8 file contents") val content: String,
)

@Generable data class WriteFileResult(val bytesWritten: Long)

tools {
    tool<WriteFileArgs, WriteFileResult>("write_file", "Writes content to a file") { args ->
        File(args.path).writeText(args.content)
        WriteFileResult(args.content.length.toLong())
    }
}
```

**`skill { tools(...) }`** — marks a skill as LLM-driven. The listed tool names are the ones the model may call. The model decides which tools to call and in what order.

**`onToolUse { name, args, result -> }`** — fires after every action tool execution. Useful for logging, tracing, and test assertions.

**`onKnowledgeUsed { name, content -> }`** — fires when the LLM fetches a knowledge entry. Receives the key name and loaded content. Does not fire for action tools.

**`onSkillChosen { name -> }`** — fires when the agent selects a skill to execute. Works with all routing strategies — manual `skillSelection {}`, LLM, and first-match.

```kotlin
val a = agent<String, String>("coder") {
    model { ollama("llama3") }
    skills { skill<String, String>("write", "Write Kotlin code") {
        tools()
        knowledge("style-guide", "Coding conventions") { loadFile("style.md") }
        knowledge("examples",    "Few-shot examples")  { loadFile("examples.kt") }
    }}
    onSkillChosen    { name          -> log("Skill: $name") }
    onKnowledgeUsed  { name, content -> log("Loaded: $name (${content.length} chars)") }
    onToolUse        { name, _, result -> log("Tool: $name = $result") }
}
// System prompt lists style-guide and examples as callable tools alongside action tools.
// Content is only fetched when the LLM decides it needs it.
```

### Inline tool-call fallback (Ollama, models without native tool support)

Some Ollama models — `gemma3`, certain Mistral variants, smaller community models — don't accept the native `tools: [...]` field on `/api/chat` and reject the request with `{"error":"... does not support tools"}`. Without recovery, the agent fails to start.

`OllamaClient.chat` recovers transparently: on the capability error, it retries the same request **once** with the native `tools` field stripped and the tool catalog injected into a system message in inline JSON tool-call format:

```
{"tool":"<tool_name>","arguments":{<key>:<value>, ...}}
```

The model emits a single JSON object per call; `InlineToolCallParser` consumes it and the agentic loop proceeds normally. Your existing `system` message is preserved — the inline format prompt is appended into a single system message, not duplicated.

A per-instance latch records the model's incapability, so subsequent `chat()` calls in the same agentic loop skip the native attempt and go straight to the inline path (one HTTP roundtrip per turn instead of two).

```kotlin
val a = agent<String, String>("calc") {
    // gemma3:4b doesn't support native tools — the fallback drives it via inline JSON
    model { ollama("gemma3:4b"); host = "localhost"; port = 11434 }
    lateinit var evaluate: Tool<Map<String, Any?>, Any?>
    tools { evaluate = tool("evaluate", "Evaluate an arithmetic expression") { args -> eval(args["expression"]!!) } }
    skills { skill<String, String>("calc", "Compute") { tools(evaluate) } }
}
a("Compute (2+3)*4")  // works — agent invokes evaluate via inline tool call, returns "20"
```

Only the `does not support tools` capability error triggers the fallback. Other provider errors — auth failures, model-not-found, transport — propagate as `LlmProviderException` (#702). Established by issues #702 (provider-error surfacing) and #706 (inline fallback).

### Tool authorization model

**The `skill { tools(...) }` declaration is authorization, enforced at execution.** Every agentic invocation builds a per-skill allowlist and the runtime refuses to execute any tool not in it. The system prompt's "Available tools" listing is descriptive — what the LLM is told it can call — but it is not the security boundary. Even if the model emits a tool name it was never shown (hallucination, jailbreak, or model from a different family), the runtime rejects it.

The allowlist for an agentic invocation:

```
skill.toolNames                          (what the skill explicitly listed)
∪ agent.autoToolNames                    (auto-injected agent capabilities)
∪ memory_read / memory_write / memory_search   (when memory { } is configured)
∪ skill.knowledge() entries              (lazy knowledge providers, exposed as tools)
```

Anything outside that set is rejected with:

```
IllegalStateException: Tool 'X' is not allowed for skill 'Y'. Allowed: [a, b, c]
```

The error names the offending skill and lists only the allowed tools — it does **not** leak the wider `agent.toolMap` to the model or to logs.

**Practical guidance.** Tools registered on the agent (`tools { tool(...) }`) are pooled at the agent level, but they are **not** auto-available to every skill — each skill must opt in via `tools(name)`. For dangerous tools (`shell`, `writeFile`, `deploy`, anything that hits production), the safest pattern is:

- Declare them only on the skill that needs them.
- Don't rely on the system prompt's "Available tools" list as a fence; it isn't one.
- Use a typo-safe `tools(...)` call — the framework fails fast at agent construction if a name doesn't exist.

### Skill Selection

When an agent has multiple skills with the same type signature, the framework decides which one to run. Three strategies, in priority order:

**1. Manual routing via `skillSelection {}`** — deterministic, zero LLM cost. This can be a simple predicate, a `when`, or any other Kotlin logic that returns a skill name:

```kotlin
val assistant = agent<String, String>("assistant") {
    model { ollama("llama3") }
    skills {
        skill<String, String>("upper", "Convert text to uppercase") {
            implementedBy { it.uppercase() }
        }
        skill<String, String>("lower", "Convert text to lowercase") {
            implementedBy { it.lowercase() }
        }
    }
    skillSelection { input ->
        if (input.startsWith("UP:")) "upper" else "lower"
    }
}

assistant("UP:hello")  // → "UP:HELLO"
assistant("HELLO")     // → "hello"
```

**2. LLM routing** — automatic when `model {}` is configured and multiple skills match. One cheap routing turn before the main agentic loop — the LLM reads all candidate `toLlmDescription()` outputs and picks a skill name:

```kotlin
val assistant = agent<String, String>("assistant") {
    model { ollama("gpt-oss:120b-cloud"); temperature = 0.0 }
    skills {
        skill<String, String>("summarize", "Summarize the given text into a brief summary") { tools() }
        skill<String, String>("translate-to-french", "Translate the given text to French") { tools() }
    }
    onSkillChosen { name -> println("Routed to: $name") }
}

assistant("Translate this to French: Hello world")
// Routed to: translate-to-french
// → "Bonjour le monde"
```

**3. First-match fallback** — when there is no `skillSelection {}` and no model-based routing, the first type-compatible skill wins (backward compatible).

| Condition | Strategy |
|-----------|----------|
| `skillSelection {}` set | Manual routing — always wins |
| Multiple candidates + `model {}` | LLM routing turn |
| Single candidate | Direct — no routing needed |
| Multiple candidates, no model | First match |

---

**Typed output** — use `transformOutput { }` on a skill when the agent's `OUT` type isn't `String`:

```kotlin
val compute = agent<String, Int>("calculator") {
    model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
    lateinit var add: Tool<Map<String, Any?>, Any?>
    lateinit var power: Tool<Map<String, Any?>, Any?>
    tools {
        add   = tool("add",   "Add two numbers. Args: a, b")             { args -> num(args, "a") + num(args, "b") }
        power = tool("power", "Raise base to exponent. Args: base, exp") { args -> Math.pow(num(args, "base"), num(args, "exp")) }
    }
    skills { skill<String, Int>("solve", "Evaluate arithmetic expressions") {
        tools(add, power)
        transformOutput { it.trim().toIntOrNull() ?: Regex("-?\\d+").find(it)?.value?.toInt() ?: error("No int in: $it") }
    }}
}

val result: Int = compute("Calculate 2^10")   // → 1024
```

**Budget control** — prevent runaway loops:

```kotlin
model { ollama("llama3") }
budget { maxTurns = 10 }   // throws BudgetExceededException after 10 turns
```

---
