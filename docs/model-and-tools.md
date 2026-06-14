[← Back to README](../README.md)

## Model & Tool Calling

Attach a model to an agent and mark a skill as agentic with `tools(...)`. The framework runs a multi-turn loop — model calls tools, results flow back, model produces the final answer.

> See [providers.md](providers.md) for the per-provider capability matrix (modality input, reasoning, caching, tool-choice, constrained decoding, streaming). What follows is the DSL surface.

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

**`model { }`** — configures the LLM backend. Eight providers ship today (see [providers.md](providers.md) for the support matrix):

- `model { ollama("gpt-oss:120b-cloud"); host = "..."; port = 11434; temperature = 0.0 }` — local or cloud Ollama; auto-fallback to inline JSON tool-call format for models without native tool support (#706).
- `model { claude("claude-opus-4-7"); apiKey = System.getenv("ANTHROPIC_API_KEY"); temperature = 0.0; maxTokens = 4096 }` — Anthropic Messages API; maps `LlmMessage` / `LlmResponse` to Anthropic's structured `tool_use` / `tool_result` content blocks; tools advertise as `input_schema` (Anthropic's spelling) (#1644).
- `model { openai("gpt-4o"); apiKey = System.getenv("OPENAI_API_KEY"); temperature = 0.0; maxTokens = 4096 }` — OpenAI Chat Completions; assistant `tool_calls` paired with `tool_call_id`-tagged tool messages via synthesized ids per request; `function.arguments` rides the wire as a stringified JSON; tools advertise as `parameters` (OpenAI's spelling) (#1656).
- `model { deepseek("deepseek-v4-flash"); apiKey = System.getenv("DEEPSEEK_API_KEY") }` — DeepSeek's OpenAI-compatible API; shares the OpenAI adapter's wire shape (#1949 family).
- `model { kimi("kimi-k2-0905-preview"); apiKey = System.getenv("MOONSHOT_API_KEY") }` — Moonshot's Kimi via its OpenAI-compatible API; extends the OpenAI adapter (#2697).
- `model { openrouter("anthropic/claude-3.5-sonnet"); apiKey = System.getenv("OPENROUTER_API_KEY") }` — OpenRouter's OpenAI-compatible gateway to many upstream models; extends the OpenAI adapter (#2701).
- `model { perplexity("sonar-pro"); apiKey = System.getenv("PERPLEXITY_API_KEY") }` — Perplexity's OpenAI-compatible Sonar API with web-grounded answers; extends the OpenAI adapter, keeps constrained decoding on (#3675). Distinct from the `perplexitySearch` *tool* (#3676), which any agent can register regardless of its own model.
- `model { gemini("gemini-2.5-flash"); apiKey = System.getenv("GEMINI_API_KEY") }` — Google Gemini's Generative Language API; a full from-scratch adapter (not OpenAI-compatible) with `contents`/`parts`, `functionDeclarations` tool calling, native SSE streaming, `responseJsonSchema` constrained decoding, thought-summary reasoning, and `inlineData` vision (#1917).

All eight providers share the `ModelClient` interface — switching providers is a one-line DSL change. The injectable `client = ...` escape hatch is still there for test stubs or custom adapters.

#### Reasoning / thinking (opt-in, #2406)

`model { reasoning(budgetTokens = 2048, effort = ReasoningEffort.HIGH) }` asks the provider to expose its reasoning. When enabled, a model's reasoning streams **separately from its answer** as `AgentEvent.Reasoning` (and `LlmChunk.ReasoningDelta`), and accumulates on `LlmResponse.reasoning`. It's **off by default** — no extra cost or behavior change unless you call `reasoning(...)`.

```kotlin
model { claude("claude-opus-4-7"); apiKey = ...; reasoning(budgetTokens = 4096) }

agent.session(input).events.collect { event ->
    when (event) {
        is AgentEvent.Reasoning -> renderThinking(event.text)  // live reasoning, not a spinner
        is AgentEvent.Token     -> renderAnswer(event.text)
        else -> {}
    }
}
```

| Provider | Mechanism | Reasoning text? |
|---|---|---|
| Anthropic | `thinking` (extended thinking); `budgetTokens` sets the budget; temperature is forced to 1 (Anthropic constraint) | yes |
| DeepSeek | `reasoning_content` — use a reasoner model (e.g. `deepseek-reasoner`) | yes |
| Ollama | `think: true`; reasoning arrives in `message.thinking` | yes |
| OpenAI | `reasoning_effort` from `effort`; reasoning **token count** via `TokenUsage.reasoningTokens` | **no** — Chat Completions returns no reasoning text on the wire; Responses-API summaries are out of scope |

Reasoning rides the live session stream (`AgentEvent`), not the post-hoc audit `PipelineEvent`. The tracing bridges (OTel / LangSmith / Langfuse) record reasoning *length* only, and the JSONL audit exporter omits it entirely — reasoning is high-volume and potentially sensitive, so it stays a live-stream signal.

#### Request timeouts (#2850)

Every built-in adapter ships with a 5-minute request timeout (and 10-second connect timeout) so long Sonnet turns, big Ollama generations, and extended-thinking calls don't get cut off mid-stream by the JDK HttpClient. Earlier 0.6.x lines hardcoded 60s on Claude/OpenAI/DeepSeek — that floor turned out to be too tight in production and was bumped to 300s in 0.6.5.

Override per-agent when needed via the `model { }` DSL:

```kotlin
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

model {
    claude("claude-opus-4-7"); apiKey = System.getenv("ANTHROPIC_API_KEY")
    requestTimeout = 10.minutes      // wall-clock cap on a single LLM request
    connectTimeout = 5.seconds       // TCP connect timeout — almost never needs tuning
}
```

Both fields default to `null`, meaning the adapter falls back to its own `DEFAULT_REQUEST_TIMEOUT` / `DEFAULT_CONNECT_TIMEOUT` constants (`ClaudeClient.DEFAULT_REQUEST_TIMEOUT`, `OpenAiClient.DEFAULT_REQUEST_TIMEOUT`, `OllamaClient.DEFAULT_REQUEST_TIMEOUT`). The DSL setters work on every provider — Ollama, Claude, OpenAI, and DeepSeek — through `ModelConfig.requestTimeout` / `connectTimeout`, which `defaultClientFor()` threads into each adapter ctor. When the cap is hit the JDK HttpClient surfaces a `HttpTimeoutException: request timed out` and the streaming `Flow` is torn down — tune up rather than fight the symptom.

#### Sharing a networking surface (#2385)

By default every provider client builds its **own** `java.net.http.HttpClient` — fine for one agent, wasteful for a long-lived process running many. Each instance carries its own selector thread, executor, and connection pool. When you need one networking surface across the fleet — a shared connection pool, a bounded executor that rate-limits concurrent LLM calls, an outbound proxy, or an `HttpClient` already wired to your metrics — inject it via `model { httpClient = … }`:

```kotlin
// One client for the whole JVM: a Semaphore-bounded executor caps concurrent
// LLM calls across every agent; the connection pool + proxy are shared.
val shared: HttpClient = HttpClient.newBuilder()
    .executor(boundedExecutor(maxConcurrent = 8))
    .proxy(ProxySelector.of(InetSocketAddress("proxy.internal", 3128)))
    .build()

val agent = agent<String, String>("kyc") {
    model { claude("claude-opus-4-7"); apiKey = ...; httpClient = shared }
    // …
}
```

- **Opt-in, never automatic.** `httpClient` defaults to `null` → each client builds its own, byte-for-byte unchanged. Existing code is unaffected.
- **Every provider.** `ModelConfig.httpClient` is threaded by `defaultClientFor()` into all eight adapters (Ollama / Claude / OpenAI / DeepSeek / Kimi / OpenRouter / Perplexity / Gemini); DeepSeek, Kimi, OpenRouter, and Perplexity inherit it through their `OpenAiClient` superclass, while Gemini takes it directly like Claude.
- **You own the policy.** The framework provides the *seam*, not the policy — rate limiting, circuit breaking, and bulkheading live in *your* `HttpClient` (e.g. a `Semaphore`-bounded `executor`). The injected client is used verbatim, so its own `connectTimeout` wins over the DSL `connectTimeout` field (the per-request `requestTimeout` still applies, since it rides on each `HttpRequest`).

**`tools { tool(name, description) { args -> } }`** — registers callable tools. Each tool receives a `Map<String, Any?>` of arguments and returns any value.

**`tools { tool<Args, Result>(name, description) { args -> } }`** — typed variant. `Args` must be `@Generable`; the framework deserializes the model's arguments into a typed instance via reflection (`KClass.constructFromMap`) before invoking the executor. The provider envelope advertises a real JSON Schema generated from `Args::class.jsonSchema()` (proper `properties`, `required`, `@Guide` descriptions per field) instead of the legacy `properties: {}, additionalProperties: true`. Deserialization failures (missing required field, wrong type) route through `onError { invalidArgs { ... } }` like JSON-parse failures, not `executionError`. The returned handle implements provider-neutral `Tool<Args, Result>`, the same boundary shape used by MCP-discovered tools.

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

**Provider constrained decoding for `@Generable` outputs** — when an agentic skill returns an `@Generable` type and does not provide a custom `transformOutput { }`, the agentic loop passes that type's JSON Schema to clients that report `supportsConstrainedDecoding()`.

Provider mappings:

| Provider | Wire shape |
|---|---|
| OpenAI | `response_format: { type: "json_schema", json_schema: { name, schema, strict: true } }` |
| Ollama | `format: <json schema>` |
| Anthropic | A forced `structured_output` tool with `input_schema: <json schema>`; its tool input is converted back into final JSON text before output parsing. |

This is a first-line defense: the provider is asked to produce the typed shape up front. The existing `@Generable` deserializer, tool-output wrapping, and repair/error paths remain defense-in-depth for unsupported clients, provider drift, and malformed responses.

**`onToolUse { name, args, result -> }`** — fires after every action tool execution. Useful for logging, tracing, and test assertions.

**`onKnowledgeUsed { name, content -> }`** — fires when the LLM fetches a knowledge entry. Receives the key name and loaded content. Does not fire for action tools.

> **Query-aware knowledge (#3863):** `knowledge(key, description, retriever)` takes a `KnowledgeRetriever` instead of a static provider — the entry surfaces as a knowledge tool with a `query` argument, fetched on demand and never inlined into the prompt. The `:agents-kt-rag` module backs this with any `EmbeddingStore` — see [rag.md](rag.md).

**Typed tool hooks (#4493)** — observation with the tool's `@Generable` Args type instead of a raw map (decode failures skip silently; never blocks or rewrites — that's the interceptors' job; chains with untyped listeners):

```kotlin
agent.onToolCall<ChargeArgs>("charge_card") { args -> audit(args.amount) }          // pre-execution
agent.onToolResult<ChargeArgs>("charge_card") { args, result -> reconcile(args, result) }
```

**`onSkillChosen { name -> }`** — fires when the agent selects a skill to execute. Works with all routing strategies — manual `skillSelection {}`, LLM, and single-candidate direct routing.

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

Anything outside that set is rejected. Before 0.6.3 the runtime threw `IllegalStateException` and killed the loop; since #2476 the rejection is **recoverable** — the runtime appends a tool-result error message naming the bad call and listing the skill's allowed tools, and the loop continues so the model gets a turn to self-correct. The disallowed executor still never runs (authorization boundary unchanged), and the skill's allowlist is the only set named — no leak of the wider `agent.toolMap` to the model or to logs.

```
ERROR: Tool 'X' is unknown for skill 'Y'. Allowed tools: a, b, c. Pick one of the allowed tools or return a final text answer.
```

Streaming consumers see `AgentEvent.ToolCallFinished(isError = true)` for the rejected call. For audit-stream consumers, 0.6.4 adds a typed `PipelineEvent.ToolHallucinated` event (#2757) — distinct from policy denial or executor errors, so auditors can grep by event class rather than parsing the error message body. Wire via `agent.onToolHallucinated { name, args, allowedTools -> ... }` or pick it up automatically through `agent.observe { }`.

**Practical guidance.** Tools registered on the agent (`tools { tool(...) }`) are pooled at the agent level, but they are **not** auto-available to every skill — each skill must opt in via `tools(name)`. For dangerous tools (`shell`, `writeFile`, `deploy`, anything that hits production), the safest pattern is:

- Declare them only on the skill that needs them.
- Don't rely on the system prompt's "Available tools" list as a fence; it isn't one.
- Use a typo-safe `tools(...)` call — the framework fails fast at agent construction if a name doesn't exist.

### Declarative tool policy DSL

Tools can also declare what they are expected to touch. In 0.6.x this was declarative only; **since 0.7.0 the declared filesystem stance is enforced** by the Layer-1 path gate (#2890), subprocess tools opt into Layer-2 OS confinement through `processTool` (#1916), and the `executor { args, env -> }` shape gives bodies a policy-gated `ToolEnvironment` (#2889). In-process Kotlin lambda side effects are still not sandboxed — see the [threat model](threat-model.md) for the full enforcement table.

```kotlin
tools {
    val readUploadedDocument = tool("readUploadedDocument") {
        description("Read an uploaded KYC document")
        policy {
            risk = ToolRisk.Medium
            filesystem {
                read("/uploads/kyc/**")
                writeNone()
            }
            network { denyAll() }
            environment { allow("OCR_REGION") }
        }
        executor { args ->
            Files.readString(Path.of(args["path"].toString()))
        }
    }
}
```

Policy fields:

| Field | DSL |
|---|---|
| Risk | `ToolRisk.Low`, `Medium`, `High`, `Critical` |
| Filesystem | `read(glob)`, `write(glob)`, `readNone()`, `writeNone()` |
| Network | `allow(host)`, `denyAll()`, `allowAll()` |
| Environment | `allow(varName)`, `denyAll()` |

`network { allowAll() }` logs a warning when the policy is built so broad egress is visible during review. `ToolPolicy` exposes `toManifestMap()`, `toManifestJson()`, and `toManifestYaml()` so the permission-manifest module can capture the policy verbatim. `PipelineEvent.ToolCalled` includes `toolPolicyRisk` and `usedDeclaredCapability`; the JSONL audit exporter writes those fields too.

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

**3. Fail-loud on ambiguity** — when there is no `skillSelection {}` and no model-based routing, multiple type-compatible skills throw `SkillRoutingException` at invocation (#3087, since 0.7.21). Silent first-match routing is disallowed — routing must be explicit and auditable. Add a `skillSelection { }` selector or configure a `model { }` for LLM routing. (The LLM router also fails loud: below-threshold confidence or an unknown skill name throws rather than guessing.)

| Condition | Strategy |
|-----------|----------|
| `skillSelection {}` set | Manual routing — always wins |
| Multiple candidates + `model {}` | LLM routing turn |
| Single candidate | Direct — no routing needed |
| Multiple candidates, no model | `SkillRoutingException` — add a selector or a model |

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

**History compression (#3865 Phase 1)** — budgets *detect* context-window pressure; compression *relieves* it. When the trigger fires, the conversation middle is replaced with one deterministic digest message before the model call — leading system messages and the most recent turns stay untouched, and the preserved window extends backward so a tool result is never orphaned from its `tool_call`:

```kotlin
agent<String, String>("long-running") {
    historyCompression {
        triggerMessages = 40         // compress when history exceeds N messages (default 40)
        preserveRecent = 4           // most recent N messages untouched (default 4)
        // triggerWhen { messages -> ... }    // custom deterministic trigger
        // summarizer { messages -> ... }     // custom digest (e.g. a cheap model);
        //                                    // a thrown exception skips compression, never fails the run
    }
    onHistoryCompressed { result -> log("compressed ${result.replacedCount} messages") }
}
```

**Strategies (#4492):** `strategy = CompactionStrategy.SlidingWindow(keepRecent = 6)` drops the middle entirely (one elision marker, zero summarizer cost; `keepRecent` overrides `preserveRecent`); `CompactionStrategy.Custom { middle -> replacement }` takes full control; the default stays `Summarize` (the digest behavior above). All strategies degrade to an uncompressed turn on failure.

Rides the `onBeforeTurn` interceptor seam (`Decision.ProceedWith` replaces the loop history permanently, so compression happens once per trigger, not per turn). Observability: `onHistoryCompressed { }`, `PipelineEvent.HistoryCompressed` via `observe { }` (counts only — no conversation content in audit rows), JSONL audit rows, and OTel / LangSmith / Langfuse bridge events. The default summarizer is a deterministic extractive digest — no LLM call, replayable. Tiered memory (hot/warm/archival) is Phase 2 (#3865).

---
