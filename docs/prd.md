# Agents.KT

## Typed Kotlin DSL Framework for AI Agent Systems

### *Define Freely. Compose Strictly. Ship Reliably.*

---

**Product Requirements Document — Version 1.4**
**March 2026 · Public design document**

> This PRD is the living design vision for Agents.KT. It mixes **shipped**, **in-progress**, and
> **planned/exploratory** capabilities — treat forward-looking sections as direction, not a shipped-
> feature list. For what is actually released, see [CHANGELOG.md](../CHANGELOG.md), the
> [roadmap](roadmap.md), and the [README](../README.md).

K.Skobeltsyn Studio  
Konstantin Skobeltsyn, CEO

---

## 1. Executive Summary

**Agents.KT** is a typed, two-layer Kotlin DSL framework for building AI agent systems with compile-time safety guarantees. Every agent is a generic function `Agent<IN, OUT>` — it consumes a typed input and **must** produce a typed output. This single constraint enforces a typed contract through the compiler: one agent, one output type, one contract.

The framework separates **agent definitions** (Layer 1 — what agents can do, what they know) from **organizational structure** (Layer 2 — who manages whom, with what authority). The framework validates the assembly at every boundary: types must chain (compiler-checked via generics), tool grants must satisfy (construction-time), delegation must be acyclic (construction-time), branches must be exhaustive (construction-time).

> **Architecture in Three Lines**
>
> **Layer 1 — Agent Definitions:** `agent<IN, OUT>("name") { skills { } tools { } knowledge { } }`
>
> **Layer 2 — Structure DSL:** `structure("name") { root(agent) { delegates(child) { grants { } } } }`
>
> **Composition:** `val pipeline = specMaster then coder then reviewer` — compiler checks every `then`

Agents are **A2A-compatible by design** (auto-generated AgentCards), **distributable as JARs** (drop into a folder to assemble a team), **testable via AgentUnit** (from deterministic unit tests to semantic LLM-as-judge), built with a **Gradle plugin + standalone CLI**, and **installable without JRE** via native binaries through brew, npm, pip, curl, or apt.

---

## 2. Problem Statement

### 2.1 Industry Pain Points

- **No typed contracts.** Agent frameworks allow god-agents with untyped inputs and outputs. No framework enforces typed I/O contracts through the compiler.
- **Runtime type mismatches.** Agent A outputs X, agent B expects Y — discovered in production. No compile-time pipeline type checking exists.
- **Ad-hoc permissions.** No framework enforces which agent can call which tools at compile time.
- **Flat architectures.** No framework models hierarchical delegation. Real-world agent systems have managers, specialists, and chains of command.
- **Scattered knowledge.** How to perform a skill is scattered across prompts, hardcoded strings, and config files with no structure or reusability.
- **No testing framework.** Agent quality assurance is bolted on. No xUnit equivalent exists for non-deterministic agent systems.
- **No distribution model.** No standard way to package, version, and distribute agents as reusable components.
- **JVM gap.** Zero convention-over-configuration agent frameworks for the JVM despite massive enterprise workloads.
- **JRE barrier.** JVM frameworks require Java installation — a dealbreaker for Python/JS developers and quick adoption. No JVM agent framework offers native binary distribution.
- **Manual interoperability.** A2A-compatible agent descriptions require manual JSON authoring.
- **Untyped LLM output.** LLMs return strings. Parsing, validating, and deserializing LLM-generated JSON into domain types is manual, brittle, and repeated in every project. No framework connects the agent's `OUT` type to the LLM's output format at compile time.

### 2.2 Target Users

- Kotlin/JVM backend developers building AI-powered services
- Teams migrating from Python agent frameworks seeking production reliability
- Enterprises requiring auditable, testable, permission-controlled AI agent hierarchies
- Architects designing multi-agent systems who need compile-time structural validation
- Teams building A2A-interoperable agent networks who want type safety internally

---

## 3. Design Principles

1. **`Agent<IN, OUT>` is the atom.** Every agent is a typed function. One input type, one output type, one contract. The compiler enforces this — `Any` is forbidden.

2. **Skills are independently typed functions.** An agent's skills each have their own `<IN, OUT>`. At least one must produce the agent's `OUT` type. Utility skills (like spell-checking) are welcome.

3. **Define Freely, Compose Strictly.** Agent definitions are unconstrained. Structure assembly is compiler-validated. Separation prevents both over-engineering and runtime surprises.

4. **Fractal composition.** Skills can be implemented by tools, agents, pipelines of agents, forums, or branches — recursively. It's agents all the way down.

5. **Convention over Configuration.** File location determines role. Sensible defaults for everything. Zero-config to start.

6. **A2A-compatible by design.** Every agent definition auto-generates a valid A2A AgentCard.

7. **Distribute as JARs, install without Java.** Agents are packaged, versioned, and distributed through Maven infrastructure. Drop JARs in a folder — get a team. CLI is a native binary — no JRE needed. Install via brew, npm, pip, curl, or apt.

8. **Test like code.** AgentUnit provides deterministic, structural, semantic, and behavioral assertions with Skill Coverage metrics.

9. **Typed I/O end to end.** Agent inputs and outputs are data classes. `@Generable` makes them LLM-parseable at compile time — JSON Schema, lenient deserializer, and prompt fragment generated automatically. No runtime boilerplate, no untyped JSON.

10. **Real Artists Ship.** Pragmatic defaults. Working solutions over theoretical perfection.

---

## 4. Protocol Stack

Agents.KT occupies the **application layer** in a three-layer protocol stack:

| Layer | Protocol | Responsibility |
|-------|----------|---------------|
| Application | **Agents.KT** | Build, validate, compose, test, distribute agents |
| Agent-to-Agent | **A2A** (Google/Linux Foundation) | Cross-system discovery and communication |
| Tool Execution | **MCP** (Anthropic/Linux Foundation) | `Tool<IN, OUT>` base interface; `McpTool` inherits and adds protocol |

**`Tool<IN, OUT>` is the base abstraction.** Local tools are direct Kotlin functions with zero protocol overhead. `McpTool<IN, OUT>` extends `Tool` and adds MCP schemas + transport. This means:

- **Local tools are fast** — no serialization, no JSON-RPC, direct function call
- **MCP tools are typed** — remote MCP server tools wrapped with optional `@Generable` types for compile-time safety
- **Agents can expose skills as MCP servers** — schemas generated from `@Generable` types on demand, not eagerly

Agents.KT agents are **A2A servers** (exposing skills via AgentCard), **MCP clients** (consuming external tools via `McpTool`), and optionally **MCP servers** (exposing skills as tools).

---

## 5. Type System: `Agent<IN, OUT>`

### 5.1 The Core Constraint

Every agent is a generic function with exactly one input type and one output type:

```kotlin
val specMaster = agent<TaskRequest, Specification>("spec-master") { ... }
val coder      = agent<Specification, CodeBundle>("coder") { ... }
val reviewer   = agent<CodeBundle, ReviewResult>("reviewer") { ... }
```

This enforces typed contracts through the compiler:

```kotlin
// ❌ COMPILE ERROR: Agent type parameters cannot be Any.
// Each agent must have specific types to enforce a typed contract.
val god = agent<Any, Any>("everything") { ... }
```

### 5.2 Type-Safe Composition

Agents compose only when types align:

```kotlin
// ✅ Types chain: Request→Spec, Spec→Code, Code→Review
val pipeline = specMaster then coder then reviewer
// Result type: Pipeline<TaskRequest, ReviewResult>

// ❌ COMPILE ERROR: Type mismatch
// specMaster.produces = Specification, reviewer.consumes = CodeBundle
val broken = specMaster then reviewer
```

The `then` infix function enforces:

```kotlin
infix fun <A, B, C> Agent<A, B>.then(other: Agent<B, C>): Pipeline<A, C>
//                                    ↑ B must equal B ↑
```

### 5.2.1 Single-Placement Rule

Each `agent<>()` call creates a **single-placement instance** — it can participate in at most one structure (Pipeline or Forum), ever. This is enforced at construction time:

```kotlin
val a = agent<A, B>("a") {}
val b = agent<B, C>("b") {}
val c = agent<B, C>("c") {}

a then b   // ✅ "a" placed in pipeline

a then c   // ❌ IllegalArgumentException:
//    Agent "a" is already placed in pipeline.
//    Each agent instance can only participate once.
//    Create a new instance for "pipeline".
```

Cross-structure reuse is also prohibited — an agent placed in a Pipeline cannot be added to a Forum, and vice versa:

```kotlin
val a = agent<A, B>("a") {}
val b = agent<B, C>("b") {}
val c = agent<A, C>("c") {}

a then b   // ✅ "a" placed in pipeline

a * c      // ❌ IllegalArgumentException: Agent "a" is already placed in pipeline.
```

To reuse the same agent logic in multiple structures, create new instances: `agent<A, B>("a") {}`.

### 5.2.2 Skills-Only Execution

Every agent executes through skills. An agent has an optional `prompt` (base context for the LLM) and one or more skills in its `skills { }` block. At least one skill must produce the agent's `OUT` type — validated at construction.

**Pure Kotlin skill** — `implementedBy` with a plain lambda, no LLM required:

```kotlin
val parser = agent<RawText, Specification>("parser") {
    skills {
        skill<RawText, Specification>("parse", "Parses raw text into a structured specification") {
            implementedBy { input -> Specification(parse(input.text)) }
        }
    }
}
```

**LLM skill** — `model { }` configures inference; `implementedBy` delegates to tools:

```kotlin
val coder = agent<Specification, CodeBundle>("coder") {
    prompt("You are an expert Kotlin developer. Prefer immutability and coroutines.")
    model { claude("claude-sonnet-4-6"); temperature = 0.1 }
    skills {
        skill<Specification, CodeBundle>("write-code", "Writes production Kotlin code from a specification") {
            implementedBy { tools("write_file", "compile") }
        }
    }
}
```

An agent with no skills that match the required output type throws `IllegalStateException` when invoked. An agent may have utility skills with different types alongside its primary skills — all valid.

### 5.2.3 Pipeline Execution

`Pipeline` composes execution functions at construction time — each `then` chains the typed lambdas. No runtime casts, no reflection:

```kotlin
val pipeline = parser then formatter then validator
// pipeline.execution: (RawText) -> ValidationResult — composed at build time

val result = pipeline(input)  // fully type-safe invoke
```

### 5.3 Sealed Types for Rich Domain Modeling

```kotlin
sealed interface Specification {
    data class OpenAPI(val schema: JsonObject) : Specification
    data class UML(val diagram: String) : Specification
    data class Markdown(val content: String) : Specification
}

sealed interface ReviewResult {
    data class Passed(val score: Double) : ReviewResult
    data class Failed(val issues: List<Issue>) : ReviewResult
    data class NeedsRevision(val feedback: String) : ReviewResult
}
```

Sealed types enable exhaustive branching (Section 7.5).

### 5.4 Type Algebra

```
Agent<A, B>     : A → B          (typed function)
A then B        : Agent<X,Y> then Agent<Y,Z> → Pipeline<X,Z>
A * B           : Agent<X,Y> * Agent<X,Z> → Forum<X, Z>  (shared input, last agent is captain)
A / B           : Agent<X,Y> / Agent<X,Y> → Parallel<X,Y>  (fan-out; all run independently; List<Y> to next stage)
                  Liskov: declare agents as Agent<X, CommonSupertype> — implementations may return subtypes.
A.loop { }      : (Agent<X,Y> | Pipeline<X,Y>).loop { (Y) -> X? } → Loop<X,Y>
                  null = stop and return Y; non-null = feed back as next X
A.branch { }    : Agent<X, Sealed<Y>> → Branch<X, Z>
                  (each variant of Y routes to a sub-pipeline ending at Z)
```

---

## 5.5 Guided Generation: `@Generable` + `@Guide`

The agent's `OUT` type must be produced by an LLM. **Guided Generation** makes any data class LLM-parseable — no runtime boilerplate, no manual schema authoring.

Three annotations cover the entire feature:

```kotlin
@Generable("Overall quality assessment of a code review")
data class ReviewResult(
    @Guide("Overall score from 0.0 to 1.0. Strict: < 0.6 means fail.")
    val score: Double,
    @Guide("One-sentence verdict: 'approved', 'needs revision', or 'rejected'.")
    val verdict: String,
    @Guide("Ordered list of specific issues found, or empty if approved.")
    val issues: List<String>,
)
```

**`@Generable(description: String = "")`** — marks a class as an LLM generation target. The optional `description` parameter describes what this type represents and appears in auto-generated skill descriptions and type documentation. Applied to the class.

**`@Guide(description: String)`** — per-field (or per sealed variant) guidance text. Tells the LLM what the field means, its range, format, or constraints. Applied to constructor parameters or sealed subclasses.

**`@LlmDescription(text: String)`** — overrides the auto-generated `toLlmDescription()` verbatim for the rare case where the generated markdown doesn't fit. Applied to the class.

### Artifacts (runtime reflection)

From a single `@Generable` class, five artifacts are available at runtime:

| Artifact | API | Use |
|----------|-----|-----|
| LLM description | `ReviewResult::class.toLlmDescription()` | Convention-over-configuration markdown: class name, description, fields, types, `@Guide` texts |
| JSON Schema | `ReviewResult::class.jsonSchema()` | Constrained decoding (Ollama) / JSON mode (Anthropic) |
| Prompt fragment | `ReviewResult::class.promptFragment()` | Injected into system prompt to guide output format |
| Lenient deserializer | `fromLlmOutput<ReviewResult>(String)` | Parses partial/malformed LLM JSON gracefully; `null` on unrecoverable input |
| Streaming variant | `PartiallyGenerated<ReviewResult>` | Immutable accumulator; `withField()` returns a new copy as tokens arrive |

> **Phase 1 (current):** runtime reflection via `kotlin-reflect`. No build step required.
> **Phase 2 (planned):** KSP annotation processor for compile-time schema generation and `PartiallyGenerated<T>` with fully typed nullable property access.

**`toLlmDescription()` — convention-over-configuration** markdown, auto-generated from the class itself:

```markdown
## ReviewResult

Overall quality assessment of a code review

- **score** (Double): Overall score from 0.0 to 1.0. Strict: < 0.6 means fail.
- **verdict** (String): One-sentence verdict: 'approved', 'needs revision', or 'rejected'.
- **issues** (List<String>): Ordered list of specific issues found, or empty if approved.
```

Override with `@LlmDescription` for the rare case where the generated text doesn't fit:

```kotlin
@Generable
@LlmDescription("Custom hand-written description — ignores auto-generation")
data class ReviewResult(...)
```

**Prompt fragment — what the LLM receives:**

```
Respond with a JSON object matching this structure:
{
  "score":   <Double: Overall score from 0.0 to 1.0. Strict: < 0.6 means fail.>,
  "verdict": <String: One-sentence verdict: 'approved', 'needs revision', or 'rejected'.>,
  "issues":  [<String: Ordered list of specific issues found, or empty if approved.>]
}
```

**Lenient deserializer** handles common LLM JSON failures: trailing commas, missing quotes, markdown code fences (` ```json ... ``` `), extra explanation text before or after the JSON block. Returns `null` on unrecoverable input.

**`PartiallyGenerated<ReviewResult>`** — a mirror of the original class with all fields nullable. Useful when the LLM streams tokens and the caller needs to react to partially-arrived fields.

### Two Enforcement Tiers

The runtime selects enforcement based on the configured model's capabilities:

| Tier | Models | Mechanism | Output guarantee |
|------|--------|-----------|-----------------|
| **1 — Constrained** | Ollama, llama.cpp, vLLM | Grammar-constrained decoding; JSON Schema fed to the sampler | Always valid JSON, always matches schema |
| **2 — Guided** | Anthropic, OpenAI, Gemini | JSON mode + `promptFragment()` injected into system prompt + `fromLlmOutput()` + fallback | Best-effort; unrecoverable → fallback or error |

Tier 2 fallback strategy (configurable per skill):

- Retry with stricter prompt
- Return `null` and let `implementedBy` handle it
- Throw `GenerationFailedException`

### Integration with Agent Types

`@Generable` works wherever the agent's type contract requires LLM output:

**Agent `OUT` type** — framework injects `promptFragment()` automatically and routes output through `fromLlmOutput()`:

```kotlin
val reviewer = agent<CodeBundle, ReviewResult>("reviewer") {
    // ReviewResult is @Generable → prompt fragment injected, output parsed automatically
    skills { skill<CodeBundle, ReviewResult>("assess", "Assesses code quality") { ... } }
}
```

**Tool arguments** — `@Generable` on tool param/return types auto-generates the tool's JSON schema:

```kotlin
tool("create_spec") {
    param<SpecRequest>("request")   // @Generable → argument schema auto-generated
    returns<Specification>()        // @Generable → return schema auto-generated
}
```

**Sealed types** — `@Generable` on a sealed interface generates a discriminator-based schema; `@Guide` on each variant explains when to use it:

```kotlin
@Generable
sealed interface ReviewDecision {
    @Guide("Use when code passes all checks without issues")
    data class Approved(val score: Double) : ReviewDecision

    @Guide("Use when code has fixable issues — provide them in order of severity")
    data class NeedsRevision(
        @Guide("Specific issues, most critical first")
        val issues: List<String>
    ) : ReviewDecision

    @Guide("Use when fundamental design must change before any fixes apply")
    data class Rejected(val reason: String) : ReviewDecision
}
```

The lenient deserializer routes to the correct subtype via the discriminator. `.branch {}` receives exhaustively matched variants — no boilerplate switch.

**Streaming** — `PartiallyGenerated<T>` integrates with `Flow`:

```kotlin
val stream: Flow<PartiallyGenerated<ReviewResult>> = reviewer.stream(code)
stream.collect { partial ->
    partial.verdict?.let { showVerdict(it) }   // non-null = this field has arrived
    partial.score?.let   { updateScore(it) }
}
```


---

## 5.6 Agentic Execution Loop

When a skill uses `model {}` + `tools()`, the framework runs a **multi-turn tool-calling loop**: the LLM generates, optionally calls tools, sees results, generates again — until it produces the agent's `OUT` type or hits a budget limit.

### The Loop

```kotlin
val coder = agent<Specification, CodeBundle>("coder") {
    model { ollama("qwen3:14b"); temperature = 0.2 }

    skills {
        skill<Specification, CodeBundle>("write-code",
            "Writes production Kotlin code from a specification") {
            knowledge("style-guide") { "Prefer val over var." }
            implementedBy { tools("write_file", "compile", "run_tests") }
        }
    }
}
```

Execution proceeds as:

```
1. Framework builds initial messages:
   - System: agent.prompt + skill.toLlmContext() + OUT type promptFragment()
   - User: serialized IN value

2. LLM generates response → framework inspects:
   a. Tool calls present → execute tools → inject results → go to 2
   b. Text matches OUT type → parse via fromLlmOutput<OUT>() → return
   c. Text doesn't match OUT → retry with correction hint → go to 2
   d. Budget exhausted → throw BudgetExceededException

3. Return typed OUT value
```

### Budget Controls

Every agentic loop has a budget. Without one, a confused agent loops forever.

```kotlin
agent<IN, OUT>("name") {
    model { ... }
    budget {
        maxTurns     = 20          // max LLM invocations in one execute()
        maxToolCalls = 50          // total tool calls across all turns
        maxTokens    = 100_000     // total input + output tokens
        maxTime      = 5.minutes   // wall-clock timeout
    }
}
```

Budget is **per-invocation** — each `agent(input)` call starts fresh. Structure-level budgets (Layer 2) cap the total across delegated agents.

### Tool Whitelist

Tools declared in `skill { tools(...) }` are the **only** tools the LLM can call. Unknown tool calls — whether from a typo, hallucination, jailbreak, or model from a different family — are rejected at the runtime boundary, not silently executed. Pre-0.6.3 this threw `IllegalStateException` and tore down the run; since #2476 the rejection is **recoverable** — the runtime appends a tool-result error to context and continues so the model gets a turn to self-correct:

```
ERROR: Tool 'delete_file' is unknown for skill 'write-code'. Allowed tools: write_file, compile, run_tests. Pick one of the allowed tools or return a final text answer.
```

The disallowed executor still never runs. For auditors, 0.6.4 surfaces hallucinations as a typed `PipelineEvent.ToolHallucinated` event (#2757) — distinct from policy denial — so the audit trail can be filtered by event class instead of by error message body.

**This is enforced runtime-side, not by the prompt.** The system prompt's "Available tools" listing is descriptive (the LLM is told what it can call), but the security boundary is the runtime allowlist:

```
allowed = skill.toolNames
        ∪ agent.autoToolNames
        ∪ memory tools (when memory configured)
        ∪ skill.knowledge() entries
```

A tool registered globally on the agent (`tools { tool(...) }`) is **not** auto-available to every skill — each skill must opt in via `tools(name)`. Tool name typos in `tools(...)` fail-fast at agent construction (no silent drops). Established by issue #630 (allowlist enforcement) and #631 (typo validation).

### Tool Constraints

Beyond whitelisting, individual tools can carry **per-skill constraints** that control *when* and *how often* the LLM can call them. Before each inference turn, the framework evaluates constraints and hides tools that aren't currently allowed — the LLM literally can't call a hidden tool.

```kotlin
skill<Specification, CodeBundle>("write-code", "Writes production Kotlin code") {
    implementedBy { tools("think", "write_file", "compile", "run_tests") }

    constraints {
        tool("think").forceAtStep(1)                    // must reason before acting
        tool("run_tests").onlyAfter("compile")          // dependency chain
        tool("compile").maxInvocations(3)               // prevent retry loops
        tool("write_file").consecutiveBlocked()         // no double-writes without thinking
    }
}
```

Constraints are a sealed hierarchy — no boolean flag soup, no impossible combinations:

```kotlin
sealed interface ToolConstraint {
    data class ForceAtStep(val step: Int) : ToolConstraint
    data class OnlyAfter(val prerequisites: List<String>) : ToolConstraint
    data class MaxInvocations(val count: Int) : ToolConstraint
    data class RequiresApproval(val message: String? = null, val timeout: Duration? = null) : ToolConstraint
    object Forbidden : ToolConstraint
    object ConsecutiveBlocked : ToolConstraint
}
```

Construction-time validation catches errors before runtime:

```
❌ ERROR: Skill "write-code" constraint references tool "deploy" 
   which is not in tools("think", "write_file", "compile", "run_tests").

❌ ERROR: Skill "analyze" has contradictory constraints on tool "search":
   ForceAtStep(1) conflicts with Forbidden.
```

This is the typed upgrade of BeeAI's `RequirementAgent` pattern: their `Rule` has 6 boolean flags (allowed, hidden, forced, prevent_stop, reason) that can conflict at runtime. Agents.KT's sealed hierarchy makes impossible states unrepresentable at compile time.

### Tool Capability Fallback (Provider-Level Recovery)

Local model ecosystems are uneven. Some Ollama models (`gemma3`, certain Mistral variants, smaller community releases) reject the native `tools` field on `/api/chat` and respond with `{"error":"... does not support tools"}`. Without recovery, every agent that pairs such a model with `tools(...)` fails to start — even though the model itself is perfectly capable of emitting structured JSON.

The framework's principle: **provider-level capability gaps don't propagate to user code.** `OllamaClient.chat` performs a one-shot retry when it observes the capability error:

1. Strips the native `tools` field from the request body.
2. Generates an inline tool-call prompt — `{"tool":"<name>","arguments":{...}}` format — listing each registered tool with its description and `@Generable`-derived argument schema.
3. Appends the inline prompt to the user's existing `system` message (not duplicated as a separate message), preserving the user's framing.
4. Re-issues the request and feeds the response through `InlineToolCallParser`, which already handles the inline format for non-native-tool models.

A `@Volatile` latch on the client instance remembers the capability outcome — subsequent `chat()` calls in the same agentic loop skip the native attempt entirely and go straight to inline mode (one HTTP roundtrip per turn instead of two).

```kotlin
agent<String, String>("calc") {
    // No native tool support — the fallback path drives it transparently.
    model { ollama("gemma3:4b") }
    tools { tool("evaluate", "Evaluate an arithmetic expression") { args -> eval(args["expression"]!!) } }
    skills { skill<String, String>("calc", "Compute") { tools("evaluate") } }
}
// Verified live with gemma3:4b: greet, evaluate("(2+3)*4")=20, fib(10)=55
```

Recovery scope is deliberately narrow:

| Provider error | Behavior |
|---|---|
| `does not support tools` | Auto-recover via inline format |
| Auth / authorization failure | Throw `LlmProviderException` |
| Model not found | Throw `LlmProviderException` |
| Transport / malformed body | Throw `LlmProviderException` |

Only the capability case auto-recovers. Other provider-boundary errors throw `LlmProviderException` so they surface clearly at the boundary instead of leaking as unparseable model "output." Established by issues #702 (provider-error surfacing) and #706 (inline fallback).

This is the same architectural move as the Anthropic/OpenAI guided-JSON fallback for models that don't expose JSON mode: the framework abstracts provider-specific protocol gaps so agent code stays portable across model families.

### Two Execution Paths (Unified)

Every agent runs through skills. Skills have two implementation paths:

| Path | Trigger | Loop | Token Cost |
|------|---------|------|-----------|
| `implementedBy { input -> ... }` | Kotlin lambda | No loop, no LLM | Zero |
| `implementedBy { tools(...) }` | LLM + tools | Agentic loop | Variable |

A single agent can have skills of both kinds.

### Error Handling

The developer owns `implementedBy` and handles domain errors inside it. The framework handles *infrastructure* errors via an `onError` callback:

```kotlin
agent<IN, OUT>("coder") {
    onError { error, context ->
        when (error) {
            is ToolCallException       -> context.retry(maxAttempts = 3)
            is BudgetExceededException -> context.returnPartial()
            is ModelUnavailableException -> context.fallbackTo(backupModel)
            else -> throw error  // propagate to caller
        }
    }
}
```

If no `onError` is defined, all exceptions propagate to the caller. The framework never silently swallows errors.

### Observability Callbacks

Three callbacks fire during agent execution. All are optional, all are agent-level:

```kotlin
val coder = agent<Specification, CodeBundle>("coder") {
    model { ollama("qwen3:14b") }
    skills {
        skill<Specification, CodeBundle>("write-code", "Writes Kotlin code") {
            knowledge("style-guide", "Coding conventions") { loadFile("style.md") }
            knowledge("examples",    "Few-shot examples")  { loadFile("examples.kt") }
            implementedBy { tools("write_file", "compile") }
        }
    }

    onSkillChosen   { name          -> log("Skill: $name") }
    onKnowledgeUsed { name, content -> log("Loaded: $name (${content.length} chars)") }
    onToolUse       { name, args, result -> log("Tool: $name($args) = $result") }
}
```

| Callback | Fires when | Arguments |
|----------|-----------|-----------|
| `onSkillChosen` | Agent selects a skill to execute | `name: String` — the selected skill's name |
| `onKnowledgeUsed` | LLM fetches a knowledge entry (tools model) | `name: String`, `content: String` — entry key and loaded content |
| `onToolUse` | An action tool completes execution | `name: String`, `args: Map<String, Any?>`, `result: Any?` |
| `onToolDenied` | An `onBeforeToolCall` `Decision.Deny` blocks a tool call | `name: String`, `args: Map<String, Any?>`, `reason: String` |

**`onSkillChosen`** fires once per invocation when the agent picks a skill — either via `skillSelection {}` predicates or LLM decision. Useful for routing visibility in multi-skill agents.

**`onKnowledgeUsed`** fires each time the LLM calls a knowledge tool (Model B — lazy loading). Does *not* fire for eager loading (`toLlmContext()`), since all entries are pre-loaded into the system prompt. Does *not* fire for action tools.

**`onToolUse`** fires after every action tool execution. Useful for logging, tracing, cost tracking, and test assertions.

**`onToolDenied`** fires when a tool call is blocked by an `onBeforeToolCall` `Decision.Deny`. The executor never runs, so `onToolUse` does *not* fire for that call; `onToolDenied` exists so audit/observability still records blocked attempts. `Agent.observe { }` surfaces the same event as `PipelineEvent.ToolDenied`. (#2395)

All callbacks are synchronous — they execute inline before the agentic loop continues. For async telemetry, emit to a channel inside the callback.

---

## 5.7 Session Model

An `AgentSession` wraps a running agent with conversation history, enabling multi-turn interaction and context management.

### Single-Shot vs Session

```kotlin
// Single-shot: stateless function call (current model)
val result: CodeBundle = coder(specification)

// Session: multi-turn with history
val session = coder.session()
val v1 = session.send("Build a REST API for user management")
val v2 = session.send("Add pagination to the list endpoint")
val v3 = session.send("Now add rate limiting")
// Each send() sees full conversation history
```

### Compaction

When conversation history approaches the model's context limit, the framework triggers **automatic compaction**:

```kotlin
agent<IN, OUT>("name") {
    session {
        compaction {
            trigger      = TokenThreshold(0.75)          // compact at 75% of context window
            strategy     = CompactionStrategy.SUMMARIZE   // default
            preserveLastN = 5                             // always keep last 5 turns verbatim
        }
    }
}
```

Compaction strategies:

- `SUMMARIZE` — dedicated summarization call; replaces history with a summary message
- `SLIDING_WINDOW` — keep last N turns, drop oldest
- `CUSTOM` — user-provided `(List<Message>) -> List<Message>` function

The summary becomes the first message in the new context, prefixed with `[Conversation compacted. Summary of prior context:]`.

### Session is Optional

Pipelines default to single-shot. Sessions are opt-in for agents that need multi-turn interaction (planning, user interviews, iterative refinement). Pipeline stages that use sessions manage their own conversation lifecycle — the pipeline does not.

---

## 5.8 Tool Hierarchy: `Tool<IN, OUT>` with MCP Inheritance

Every tool in Agents.KT is a typed function `Tool<IN, OUT>` — parallel to `Agent<IN, OUT>`. MCP is not a wrapper around tools; MCP *inherits* from tools. Local tools have zero protocol overhead. MCP tools add schema and transport.

### The Hierarchy

```kotlin
// Base — every tool is this. No schemas, no protocol, just a typed function.
interface Tool<IN, OUT> {
    val name: String
    val description: String
    suspend fun call(input: IN): OUT
}

// Local tool — Kotlin lambda, zero overhead, no serialization
class LocalTool<IN, OUT>(
    override val name: String,
    override val description: String,
    private val impl: suspend (IN) -> OUT
) : Tool<IN, OUT> {
    override suspend fun call(input: IN): OUT = impl(input)
}

// MCP tool — wraps a remote MCP server tool, adds schemas + transport
class McpTool<IN, OUT>(
    override val name: String,
    override val description: String,
    val inputSchema: JsonObject,     // from MCP server or @Generable
    val outputSchema: JsonObject?,   // from MCP server (optional per spec)
    private val transport: McpTransport,
    private val deserializer: (JsonObject) -> OUT
) : Tool<IN, OUT> {
    override suspend fun call(input: IN): OUT { /* JSON-RPC call */ }
}
```

Schemas live on `McpTool`, not on `Tool`. Local tools don't pay the schema cost.

### Three Creation Patterns

**Local typed tool** — direct Kotlin, zero overhead:

```kotlin
val writeFile = tool<WriteFileInput, WriteFileResult>(
    "write_file", "Writes content to a file"
) { input ->
    File(input.path).writeText(input.content)
    WriteFileResult(success = true, bytesWritten = input.content.length.toLong())
}
// → LocalTool<WriteFileInput, WriteFileResult> — no schemas, no MCP, just a function
```

**Simple tool** — primitive params, no data class:

```kotlin
val compile = tool("compile", "Compiles Kotlin source code") {
    param("target", STRING) { enum("jvm", "native"); default("jvm") }
    param("path", STRING) { required() }
    execute { args: JsonObject ->
        kotlinCompiler.compile(args["path"]!!.jsonPrimitive.content)
    }
}
// → LocalTool<JsonObject, CompileResult>
```

**Remote MCP tool** — from an external MCP server:

```kotlin
val mcpServer = McpClient.connect("https://mcp.github.com/sse")

// Untyped — raw MCP wire format
val rawTool: McpTool<JsonObject, JsonObject> = mcpServer.tool("create_pull_request")

// Typed wrapper — @Generable types for compile-time safety
@Generable data class PrRequest(
    @Guide("Repository in owner/name format") val repo: String,
    @Guide("PR title") val title: String,
    @Guide("Source branch") val head: String,
    val base: String = "main"
)
@Generable data class PrResult(val number: Int, val url: String)

val createPr: McpTool<PrRequest, PrResult> = mcpServer.tool("create_pull_request")
    .typed<PrRequest, PrResult>()
// inputSchema validated against MCP server's schema at connection time
```

### MCP Server Discovery

```kotlin
val coder = agent<Specification, CodeBundle>("coder") {
    mcp {
        server("github") { url = "https://mcp.github.com/sse" }
        server("filesystem") { command = "npx @modelcontextprotocol/server-filesystem /src" }
    }

    tools {
        +writeFile                              // LocalTool — zero overhead
        +compile                                // LocalTool — zero overhead
        // github/create_pull_request            — McpTool, auto-discovered
        // filesystem/read_file                  — McpTool, auto-discovered
    }
}
```

### Agent as MCP Server

Any agent can expose itself as an MCP server. Skills become MCP tools. `@Generable` types on skill IN/OUT generate `inputSchema`/`outputSchema`:

```kotlin
val server = McpServer.from(coder) {
    port = 8080
    transport = McpTransport.STREAMABLE_HTTP
    expose("write-code")        // skill → MCP tool
}
server.start()
// Now callable by Claude Code, Cursor, Copilot, or any MCP client
```

When a skill is exposed as MCP, the framework generates schemas from the skill's `@Generable` IN/OUT types *at that point* — not at tool construction. Local tools never pay schema cost unless explicitly exposed.

### McpRunner: picocli-style standalone main

For agents shipped as runnable JARs (or Docker / GraalVM native), `McpRunner` collapses lifecycle to one line:

```kotlin
fun main(args: Array<String>) = exitProcess(McpRunner.serve(coder, args) {
    port = 8080                    // overridden by --port
    expose("write-code")           // overridden by --expose (repeatable)
})
```

The runner parses CLI args (block defaults override-able by flags), starts the server, prints the listening URL, registers a JVM shutdown hook for graceful `stop()`, and blocks until SIGTERM/SIGINT. Hand-rolled CLI parser (no picocli dependency) — stays consistent with the project's "JDK 21 only, no extra deps" ethos.

Flags: `--port N`, `--expose NAME` (repeatable), `-h/--help`, `-V/--version`. This is the foundation the Gradle plugin (§14.2) and runtime distribution (§15) build on — `application { mainClass = ... }` auto-generation, GraalVM native binary, jlink runtime bundle.

### Agent Deployment Modes — library, hosted, autonomous

The same agent definition can be deployed in three ways. Each mode is one line of glue away from the next:

| Mode | Glue | Where it runs | Who can call it |
|------|------|---------------|----------------|
| **Library** | `agent<IN, OUT>("...") { skills { } }` | In the host JVM, in-process | Internal Kotlin code, fully typed |
| **Hosted** | + `McpServer.from(agent) { expose("...") }.start()` | In the host JVM, also addressable | Internal callers (typed) AND any MCP client (over HTTP) |
| **Autonomous (ejected)** | `fun main(args) = exitProcess(McpRunner.serve(agent, args))` | Its own process / JAR / Docker / native binary | Any MCP client, anywhere |

The progression matches how agents earn their independence: start as an in-process function, grow into a hosted endpoint when external callers appear, **eject** into autonomy when the deploy unit needs to be the agent itself (independent scaling, separate ops budget, language-neutral fleet). The agent definition is the same Kotlin code in all three modes — only the wiring around it changes.

This three-mode model is what §13's distributed framework, §14's Gradle plugin, and §15's runtime distribution are all in service of: making the autonomous mode as cheap operationally as the library mode is at compile time. See the [Agent Deployment Modes](https://github.com/Deep-CodeAI/Agents.KT/wiki/Agent-Deployment-Modes) wiki page for the full narrative and tradeoff table.

### Self-Hosting Documentation: The InternalsAgent Pattern

The framework eats its own dogfood. `buildInternalsAgent()` (in `agents_engine.runtime.internals`) is an `Agent<String, String>` whose skills correspond 1:1 to source files in the framework — `core_agent_kt` returns the curated docs for `Agent.kt`, `mcp_mcpclient_kt` for `McpClient.kt`, and so on. The agent is exposed via `McpServer.from(...)` over HTTP; IDE-side LLMs (Cursor, Claude Desktop, anything that speaks MCP) call those skills as tools and reason about the framework using authoritative source-file knowledge.

Two architectural ideas worth naming:

**1. Adjuncts as the single source of truth.** Each skill loads from a markdown file under `src/main/resources/internals-agent/<package>/<File>.md`. Each file begins with a YAML-style frontmatter block:

```markdown
---
description: <one-line tool description shown to the IDE LLM>
---

# <heading>
<body returned as the tool result>
```

The `description:` line is the LLM-facing tool description; the body is the tool's return value. No per-skill code edit is required to add a new file — drop in one `.md` and the next agent construction picks it up.

**2. Classpath-scan registration.** `buildInternalsAgent()` enumerates every `.md` under `internals-agent/` at construction time, deriving the skill name mechanically from the path (`internals-agent/core/Agent.md` → `core_agent_kt`). Works in both file-system layouts (dev / IDE) and JAR layouts (production / shaded distributions) via `URL.protocol` dispatch. The framework-side agent declares no `model { }` — the IDE's LLM does all the reasoning; each skill is a pure `loadResource(path)` data fetch.

The pattern generalizes: any agent whose skills are "describe a resource" can use a directory of frontmatter-headed `.md` files as its skill registry. The reduction from "63 hand-written `skill<String, String>(name, description) { implementedBy { ... } }` blocks" to "scan the classpath, loop" is the canonical version of why this pattern matters.

See `docs/internals-agent.md` for IDE-wiring instructions and the InternalsAgent quickstart (`./gradlew runInternalsAgent`).

---

## 6. Skill Model: Independent Typed Functions

A skill is an independently typed function `Skill<IN, OUT>` — it is **not** locked to the agent's type contract. An agent is a container of skills, each with its own `<IN, OUT>`. The only constraint: **at least one skill must produce the agent's `OUT` type.** This is validated at agent construction time.

Every skill has a `description` — a short text that "sells" the skill to the LLM alongside its type signature, enabling the LLM to choose the right skill for the job. Skills also carry unlimited named **`knowledge` entries**: lazy `() -> String` providers that supply context to the LLM when the skill is selected.

Skills can be defined **outside the agent** as top-level typed values and added with `+`, or **inline** inside the `skills { }` block. Top-level skills give the developer a fully typed reference — no casts needed when calling `execute()` directly.

```kotlin
// Top-level: developer holds a typed reference
val printer = skill<TaskRequest, String>("printer", "Formats and prints a task request as a string") {
    knowledge("format-rules") { "Always prefix with 'Task: '" }
    implementedBy { input -> "Task: ${input.content}" }
}

val myAgent = agent<TaskRequest, Result>("HelloWorldAgentPrinter") {
    skills {
        +printer                                                              // add pre-defined skill
        skill<String, Result>("answerer", "Produces a final Result answer") { }  // define inline
    }
}

// Developer is admin — call any skill directly with custom values
val output = printer.execute(TaskRequest("hello"))  // fully typed, no cast

// Or introspect via hashmap
myAgent.skills["printer"]                             // Map<String, Skill<*, *>>
myAgent.skills.keys                                   // ["printer", "answerer"]
```

### 6.1 Three Dimensions of a Skill

```
┌───────────────────────────────────────────┐
│                  SKILL                     │
│  "Create OpenAPI Specification"            │
│                                            │
│  WHAT     (A2A contract, public)           │
│  ├── name        — unique identifier       │
│  ├── description — "sells" skill to LLM   │  ← implemented
│  ├── tags, examples                        │
│  └── → auto-generates AgentCard.skills[]   │
│                                            │
│  KNOW-HOW (knowledge, internal)            │
│  ├── knowledge("key", "desc") { "..." }    │  ← named lazy providers, implemented
│  │   desc tells LLM what the entry holds  │
│  │   Model A — all-at-once:                │
│  │     skill.toLlmContext()                │  ← description + all entries merged
│  │   Model B — tools:                      │
│  │     skill.knowledgeTools()              │  ← LLM pulls entries by key+desc on demand
│  └── (loadFile() inside providers for     │
│       file-based content — no convention) │
│                                            │
│  HOW     (implementation, internal)        │
│  ├── implementedBy { kotlinLambda }        │  ← implemented
│  ├── tools()     — direct execution        │
│  ├── agent()     — delegate to one agent   │
│  ├── pipeline {} — sequential chain        │
│  ├── forum {}    — participants + captain    │
│  └── branch {}   — conditional routing     │
└───────────────────────────────────────────┘
```

### 6.2 Multiple Skills — Independent Types, At Least One Produces OUT

```kotlin
// Top-level skills: typed references the developer can call directly
val writeFromScratch = skill<Specification, CodeBundle>("write-from-scratch",
    "Generates Kotlin code from scratch based on a specification") {
    knowledge("style-guide") { "Prefer val over var. Use data classes for DTOs." }
    knowledge("examples") { loadExamples("code/greenfield-examples.kt") }
    implementedBy { tools("write_file", "compile") }
}

val modifyExisting = skill<ExistingCode, CodeBundle>("modify-existing",
    "Modifies existing code to satisfy a new specification without breaking existing contracts") {
    knowledge("refactor-rules") { "Preserve public API surface. Add tests for every change." }
    implementedBy { tools("read_file", "edit_file", "compile") }
}

val checkSpelling = skill<String, String>("check-spelling",
    "Checks and corrects spelling in any text string") {
    implementedBy { tools("spellcheck") }
}

val coder = agent<Specification, CodeBundle>("coder") {
    skills {
        +writeFromScratch                           // add pre-defined
        +modifyExisting                             // add pre-defined
        +checkSpelling                              // utility skill, different types
        skill<String, String>("format-code") { }    // or define inline
    }

    // ✅ At least one skill produces CodeBundle (agent's OUT) — validated at construction
    // ❌ If no skill returns CodeBundle → IllegalArgumentException
}

// Developer is admin: call any skill with custom input
writeFromScratch.execute(mySpec)       // fully typed: Specification → CodeBundle
checkSpelling.execute("some text")     // fully typed: String → String
```

### 6.3 Skill Selection

When an agent has multiple skills, selection happens in one of two primary ways:

**LLM decides** (default) — the LLM reads each skill's `description` and `knowledgeTools()` descriptions, then chooses. This is the natural path when `model {}` is configured.

**Manual `skillSelection {}` routing** — explicit Kotlin logic when deterministic routing is needed:

```kotlin
skillSelection { input ->
    when {
        input.existingCode == null -> skill("write-from-scratch")
        else                       -> skill("modify-existing")
    }
}
```

### 6.4 LLM Context Models

A skill exposes itself to the LLM through three methods:

```kotlin
skill.toLlmDescription()   // auto-generated markdown — name, types, description, knowledge index
skill.toLlmContext()        // full context: toLlmDescription() + all knowledge content
skill.knowledgeTools()      // tools model: knowledge as callable list the LLM pulls on demand
```

**`toLlmDescription()`** — convention-over-configuration: auto-generated from existing skill data, no extra annotations needed. Renders as markdown for LLM readability. When `IN`/`OUT` types carry `@Generable`, their description and field list (with `@Guide` texts) are embedded inline:

```markdown
## Skill: write-from-scratch

**Input:** Specification — A structured API specification
  - endpoints (List<String>): List of endpoint paths to implement
**Output:** CodeBundle — A bundle of generated Kotlin source files
  - source (String): The generated Kotlin source code

Generates Kotlin code from scratch based on a specification.

**Knowledge:**
- style-guide — Preferred coding style — immutability, naming, formatting
- examples — Concrete input/output pairs for few-shot prompting
- checklist — Self-verification steps before returning output
```

The full DSL that produces this:

```kotlin
skill<Specification, CodeBundle>("write-from-scratch",
    "Generates Kotlin code from scratch based on a specification") {
    knowledge("style-guide", "Preferred coding style — immutability, naming, formatting") {
        "Prefer val over var. Use data classes for DTOs."
    }
    knowledge("examples", "Concrete input/output pairs for few-shot prompting") {
        loadExamples("code/greenfield-examples.kt")
    }
    knowledge("checklist", "Self-verification steps before returning output") {
        "1. Does it compile?\n2. Are all fields non-nullable where possible?"
    }
    implementedBy { tools("write_file", "compile") }
}
```

No `input()`, `output()`, or `rule()` calls needed — the description is fully generated from `name`, `description`, `inType`, `outType`, and the knowledge index. The knowledge section lists entry names and their descriptions so the LLM sees what context is available without loading it.

For the rare case where the generated text doesn't fit, it can be replaced entirely:

```kotlin
skill<Specification, CodeBundle>("write-from-scratch", "...") {
    llmDescription("Custom markdown description overriding the generated one")
}
```

**Knowledge entry description** — each knowledge entry carries its own `description` so the LLM knows what it contains *before* deciding whether to load it. The description defaults to `""` when omitted; providing it is strongly recommended.

**Model A — All-at-once (`toLlmContext()`):** `toLlmDescription()` followed by the full content of every knowledge entry. Simple and predictable; trades token efficiency for zero round-trips.

```kotlin
val ctx = writeFromScratch.toLlmContext()
// →
// ## Skill: write-from-scratch
//
// **Input:** Specification
// **Output:** CodeBundle
//
// Generates Kotlin code from scratch based on a specification.
//
// **Knowledge:**
// - style-guide — Preferred coding style — immutability, naming, formatting
// - examples — Concrete input/output pairs for few-shot prompting
// - checklist — Self-verification steps before returning output
//
// Knowledge:
// --- style-guide ---
// Prefer val over var. Use data classes for DTOs.
// --- examples ---
// ...
```

Knowledge providers are evaluated lazily — `toLlmContext()` triggers each `() -> String` at call time.

Knowledge providers are evaluated lazily — `toLlmContext()` triggers each `() -> String` at call time, so expensive loads (file reads, DB queries) only happen when context is actually needed.

**Model B — Tools (`knowledgeTools()`):** The LLM receives only `toLlmDescription()` upfront. Knowledge entries are exposed as `KnowledgeTool` instances the LLM can call by name — like MCP tool calls. The `description` field tells the LLM what each tool contains so it can decide which to invoke.

```kotlin
data class KnowledgeTool(
    val name: String,
    val description: String,    // ← LLM reads this to decide whether to call
    val call: () -> String,     // ← lazy; nothing loads until invoked
)

val tools = writeFromScratch.knowledgeTools()
// → [
//     KnowledgeTool("style-guide", "Preferred coding style — immutability, naming, formatting", ...),
//     KnowledgeTool("examples",    "Concrete input/output pairs for few-shot prompting", ...),
//     KnowledgeTool("checklist",   "Self-verification steps before returning output", ...),
//   ]

// LLM sees the menu of descriptions, then calls only what it needs:
tools.find { it.name == "style-guide" }?.call()
// → "Prefer val over var. Use data classes for DTOs."
```

Each `call()` is lazy — nothing loads until the LLM requests it. This makes the tools model naturally token-efficient and suitable for large knowledge bases where only a fraction of entries are relevant to any given input.

**When to use each:**

| | All-at-once | Tools |
|---|---|---|
| Knowledge size | Small / always relevant | Large / conditionally relevant |
| LLM capability | Any | Requires tool-calling support |
| Token cost | Fixed (always pays full cost) | Variable (pays only for what's used) |
| Determinism | High (same context every time) | Lower (LLM chooses what to load) |

Both models coexist — the execution engine chooses based on the configured model's tool-calling capability.

---

## 7. implementedBy: Fractal Composition

A skill can be implemented by **anything that transforms IN to OUT**: tools, agents, pipelines, forums (multi-agent coordination), conditional branches, or any combination.

### 7.1 Tools (Leaf Execution)

```kotlin
skill("write-code") {
    implementedBy {
        tools("write_file", "compile")
    }
}
```

### 7.2 Agent (Single Delegation)

```kotlin
skill("expert-write") {
    implementedBy {
        agent(kotlinExpert)  // Agent<Specification, CodeBundle>
        // Must match parent agent's <IN, OUT>
    }
}
```

### 7.3 Pipeline (Sequential Chain)

```kotlin
skill("write-and-test") {
    implementedBy {
        pipeline { writer then compiler then tester }
        // writer:   Specification → RawCode
        // compiler: RawCode → CompiledCode
        // tester:   CompiledCode → CodeBundle
        // Total:    Specification → CodeBundle ✅
    }
}
```

### 7.4 Forum (Multi-Agent Discussion)

Think **jury deliberation** with explicit coordination semantics. Every forum member receives the same input, non-final agents run concurrently as participants, and one agent delivers the verdict (OUT). Convention: the last agent in the `*` chain is the captain.

Forum typing: members share the same `IN`, and the **last agent's OUT** (captain) determines the forum output.

```kotlin
// Forum: shared IN = Specs, captain's OUT = Result
val codeDiscussion = opinionsArbitrageMaster * crazyGenerator * passiveGenerator * answerMaster
// Forum<Specs, Result>

// Compose with pipeline: converter feeds the forum
val pipeline = inputToSpecsConverter then (opinionsArbitrageMaster * crazyGenerator * passiveGenerator * answerMaster)
// Pipeline<Input, Result>

skill("reliable-write") {
    implementedBy {
        forum<Specification, FinalCode> {
            participant(kotlinExpert)    // Specification → Opinion
            participant(javaConverter)   // Specification → Opinion
            captain(arbiter)             // Specification → FinalCode
            allowForumReturn(kotlinExpert)
        }
        // Captain may finalize by default; selected participants may also call forum_return
        // Forum<Specification, FinalCode>
    }
}
```

### 7.5 Parallel (Fan-Out)

All agents receive the same input, run independently, and the next pipeline stage receives `List<OUT>`.

```kotlin
// Same OUT type
val parallel = reviewerA / reviewerB / reviewerC
// Parallel<Code, Review>

// Compose in a pipeline — next stage receives List<Review>
val pipeline = coder then parallel then synthesizer
// synthesizer: Agent<List<Review>, FinalResult>
// Pipeline<Spec, FinalResult>
```

**Liskov:** Declare agents as the common supertype — implementations may return subtypes.

```kotlin
sealed interface Review
data class QuickReview(val summary: String) : Review
data class DeepReview(val issues: List<String>, val score: Double) : Review

// Both declared as Agent<Code, Review>; implementations return concrete subtypes
val quick = agent<Code, Review>("quick-reviewer") { ... }   // returns QuickReview
val deep  = agent<Code, Review>("deep-reviewer")  { ... }   // returns DeepReview

val parallel = quick / deep
// Parallel<Code, Review>  ✅ — compiler sees Review throughout

val pipeline = coder then parallel then synthesizer
// synthesizer: Agent<List<Review>, FinalResult>
```

The distinction from Forum: parallel agents do **not** share a finalizer or coordination path — each runs in isolation on the same input. Forum agents share one input and converge through a captain/finalizer.

### 7.6 Loop (Iterative Execution)

The `next` block receives the output and returns the next input to continue, or `null` to stop. Works on both agents and pipelines. Fully composable with `then`.

```kotlin
// Agent loop — while (result < 10) { result = refine(result) }
val loop = refine.loop { result -> if (result >= 10) null else result }

// Pipeline loop — iterate over a multi-step body
val loop = (normalize then amplify).loop { result ->
    if (result.score >= 0.9) null else result   // keep refining until quality threshold
}

// Compose in a pipeline — Loop<A,B> is a first-class pipeline citizen
val pipeline = prepare then loop then finalize
val result = pipeline(input)
```

The `next` block is plain Kotlin — call other agents, inspect external state, transform the output into a different input type:

```kotlin
val loop = body.loop { result ->
    when {
        result.done      -> null                       // stop
        result.needsHelp -> escalate(result)           // call another agent inline
        else             -> result.retry()             // feed back transformed
    }
}
```

**Plain `while` is also valid.** Agents and pipelines are callable functions — standard Kotlin control flow requires no DSL:

```kotlin
var result = initial
while (!isDone(result)) {
    result = pipeline(result)   // pipeline called repeatedly, no restrictions
}
```

Both patterns — `.loop {}` and `while` — coexist. Use `.loop {}` when the loop is a structural part of a larger pipeline; use `while` for ad-hoc orchestration in application code.

### 7.7 Branch (Conditional Routing on Sealed Types)

Routes the output of an agent to a different handler per sealed variant. All branches must converge to the same `OUT` type — enforced by the `BranchBuilder<OUT>` type parameter. Unhandled variants throw `IllegalStateException` at invocation.

```kotlin
sealed interface ReviewResult
data class Passed(val score: Double)           : ReviewResult
data class Failed(val issues: List<String>)    : ReviewResult
data class NeedsRevision(val feedback: String) : ReviewResult

val afterReview = reviewer.branch {
    on<Passed>()        then deployer                    // Agent<Passed, DeployResult>
    on<Failed>()        then failReporter                // Agent<Failed, DeployResult>
    on<NeedsRevision>() then (reviser then reviewer)     // Pipeline on a variant
}
// Branch<CodeBundle, DeployResult>

// Fully composable with then
val pipeline = coder then afterReview then notifier
// Pipeline<Specification, Notification>
```

Agents inside the branch receive `markPlaced("branch")` — they cannot be reused in other structures. A pipeline used as a branch handler has its agents already tracked from pipeline construction.

### 7.8 Hybrid (Mix Everything)

```kotlin
skill("supervised-write") {
    implementedBy {
        pipeline {
            tools("analyze_spec")         // my tool
            then agent(kotlinExpert)        // delegate to agent
            then forum<ReviewInput, ReviewVerdict> {
                participant(reviewer1)
                captain(reviewer2)
            }
            then tools("finalize")         // my tool again
        }
        .withRetry(maxAttempts = 3)
        .withTimeout(30.seconds)
        .withFallback(tools("manual_write"))
    }
}
```

### 7.9 A2A Remote Agent as Implementation

```kotlin
val externalReviewer = Agent.fromA2A<CodeBundle, ReviewResult>(
    "https://api.reviewbot.io/.well-known/agent.json"
)

skill("external-review") {
    implementedBy {
        pipeline {
            tools("prepare_code")            // local
            then agent(externalReviewer)      // A2A remote
            then tools("apply_fixes")        // local
        }
    }
}
```

### 7.10 Type Checking Rules

Skills are independently typed — each skill has its own `<IN, OUT>`. The agent-level constraint is:

```
Agent<X, Y> with skills:
  At least one skill must have OUT == Y  (validated at construction)
  Other skills may have any <IN, OUT>    (utility skills)

implementedBy within a skill:
  MUST produce: Skill's IN → Skill's OUT (not agent's)

tools("t1", "t2"):
  Collectively transform Skill's IN → Skill's OUT

agent(x):
  x must be Agent<Skill's IN, Skill's OUT>  (or compatible variance)

pipeline { a then b then c }:
  a.in == Skill's IN, c.out == Skill's OUT, chain links

forum { a * b * c }:
  Forum IN = a.IN = b.IN = c.IN, Forum OUT = c.OUT (captain)

parallel { a / b / c }:
  All agents share same IN and OUT (declare as common supertype for Liskov)
  Next stage receives List<OUT>

branch { on<X> then ... }:
  All branches must end at same type
```

Violations are compile errors with actionable messages:

```
❌ ERROR: Skill "write-and-test" pipeline produces CompiledCode
   but agent "coder" promises CodeBundle.
   Pipeline: writer(Spec→Raw) then compiler(Raw→Compiled)
   Missing final stage: Compiled → CodeBundle
```

### 7.11 Fractal Depth

```
project.skill["deliver-feature"]
  → pipeline { specMaster then codeMaster then deployer }
    → codeMaster.skill["produce-reviewed-code"]
      → pipeline { coder then reviewer.branch { ... } }
        → coder.skill["write-code"]
          → tools("write_file", "compile")

4 levels deep. Each level typed. Each boundary validated.
```

---

## 8. Knowledge System

### 8.1 Code-Based Knowledge

Knowledge in Agents.KT is code-based: `knowledge("key", "description") { "content" }` on `Skill`. Each entry is a named lazy `() -> String` provider. This is the only knowledge mechanism — no file-based knowledge, no separate knowledge files.

```kotlin
skill<Specification, CodeBundle>("write-code",
    "Generates Kotlin code from a specification") {
    knowledge("style-guide", "Preferred coding style") {
        "Prefer val over var. Use data classes for DTOs."
    }
    knowledge("examples", "Concrete input/output pairs") {
        loadFile("code/greenfield-examples.kt")  // loads at call time, not at construction
    }
    implementedBy { tools("write_file", "compile") }
}
```

Nothing prevents loading files — `loadFile()` is just `File(path).readText()` inside the lambda. The framework doesn't care where the string comes from. But there's no framework-managed file convention (no `skill.md`, no `reference/`, no `checklist/`). The developer decides their own file organization.

### 8.2 Shared Knowledge Packs

Knowledge packs are reusable bundles of `knowledge()` entries:

```kotlin
val kotlinBestPractices = knowledgePack("kotlin-bp") {
    knowledge("idioms", "Kotlin idiomatic patterns") { loadFile("code/kotlin-idioms.md") }
    knowledge("coroutines", "Coroutine patterns") { loadFile("code/coroutines-patterns.md") }
}

// Include in any skill
skill<Specification, CodeBundle>("write-code", "...") {
    include(kotlinBestPractices)   // merges all entries into this skill
    knowledge("own-stuff") { "..." }
}
```

### 8.3 LLM Context Delivery

Two models for delivering knowledge to the LLM (see §6.4 for full API):

| Model | API | When |
|-------|-----|------|
| All-at-once | `skill.toLlmContext()` | Small knowledge, always relevant |
| On-demand tools | `skill.knowledgeTools()` | Large knowledge, model pulls what it needs |

### 8.4 Reactive Context (Hooks)

Hooks are event-driven context injections that fire during agent execution. They push information into the agent's conversation based on runtime events — the agent doesn't pull it.

**Hooks are read-only observers, not mutation points.** A hook can inject a system reminder or log telemetry, but it cannot modify the tool call arguments, change the LLM response, or cancel an action. If you need to transform data mid-flight, that's a tool constraint (§5.6) or a pipeline stage — not a hook. This separation prevents the debugging nightmares that arise when event listeners can mutate execution state.

```kotlin
agent<IN, OUT>("coder") {
    hooks {
        beforeInference { context ->
            context.addSystemReminder("Git status: ${gitStatus()}")
        }

        afterToolCall("write_file") { call, result ->
            val diagnostics = runLinter(call.arguments["path"])
            if (diagnostics.isNotEmpty()) {
                context.addSystemReminder(
                    "Linter found ${diagnostics.size} issues:\n${diagnostics.joinToString("\n")}"
                )
            }
        }

        onBudgetThreshold(0.8) {
            context.addSystemReminder("Token usage at 80%. Prioritize completing the current task.")
        }
    }
}
```

**Typed hook payloads** — hooks carry typed event data, not generic maps:

```kotlin
agent<IN, OUT>("coder") {
    hooks {
        onSkillStart<Specification> { event ->
            log("Skill ${event.skillName} starting with ${event.input.endpoints.size} endpoints")
        }
        onToolCall<CompileRequest> { event ->
            audit(event.toolName, event.params)   // typed params, not Map<String, Any?>
        }
        onToolResult<CompileResult> { event ->
            if (!event.result.success) alertOps(event.result.errors)
        }
        onSkillComplete<CodeBundle> { event ->
            metrics(event.duration, event.tokenUsage)
        }
        onError { event ->
            alertOps(event.exception)
        }
    }
}
```

The type parameter on each hook is the expected payload type. Mismatched types are construction-time errors — not runtime `ClassCastException`s.

**Knowledge vs System Reminders:**

| | Knowledge | System Reminder |
|---|---|---|
| Timing | Loaded at skill selection | Injected at hook trigger |
| Scope | Entire skill execution | From injection point forward |
| Source | Developer-authored | Runtime events |
| Persistence | Survives compaction (re-injected) | Discarded on compaction |

**Built-in hooks** (disable with `hooks { builtins(false) }`):

- `onBudgetThreshold(0.8)` — token budget warning at 80%
- `onToolError` — inject error context when a tool call fails
- `onCompaction` — notify agent that context was compacted

### 8.5 Agent Memory

Agent memory persists across invocations — an agent accumulates knowledge over time rather than starting from zero each run.

```kotlin
agent<CodeDiff, ReviewResult>("reviewer") {
    memory {
        scope    = MemoryScope.PROJECT   // persists per-project; also: USER, GLOBAL
        file("patterns.md")              // auto-loaded into context on each invocation
        maxLines = 200                   // truncate if memory grows beyond this
    }

    skills {
        skill<CodeDiff, ReviewResult>("review", "Reviews code changes") {
            knowledge("memory-instructions") {
                "Before reviewing, consult your memory file for patterns you've seen before. " +
                "After reviewing, update your memory with new patterns discovered."
            }
            implementedBy { tools("read", "grep", "memory_read", "memory_write") }
        }
    }
}
```

When `memory {}` is configured, two tools are auto-injected: `memory_read()` and `memory_write(content)`.

Memory files live in `.agents-kt/memory/{scope}/{agent-name}/patterns.md` — plain text/markdown, human-readable, version-controllable, editable outside the framework.

### Memory Strategies *(planned)*

Memory can be segmented into typed namespaces with different retention strategies. This prevents one category of content (e.g. verbose tool outputs) from crowding out another (e.g. conversation turns):

```kotlin
agent<TaskRequest, Specification>("spec-master") {
    memory {
        scope = MemoryScope.PROJECT
        sliding<ConversationTurn>(size = 20)           // last 20 turns, FIFO
        tokenBudget<ToolResult>(maxTokens = 4000)      // tool results capped by tokens
        summarized<ResearchNote>(model = "haiku")       // auto-summarize when budget exceeded
    }
}
```

| Strategy | Type | Behavior |
|----------|------|----------|
| `sliding<T>(size)` | Keep last N items | FIFO — oldest dropped when full |
| `tokenBudget<T>(maxTokens)` | Keep items until token limit | Oldest items dropped first |
| `summarized<T>(model)` | Auto-summarize on overflow | Dedicated LLM call compresses old entries |
| `unbounded<T>()` | Keep everything | Only bounded by storage — use with caution |

The type parameter ensures `Memory<ConversationTurn>` and `Memory<ToolResult>` are separate namespaces. An agent can't accidentally fill its conversation window with tool outputs — each type has its own budget.

**Shipped (#4515): the strategy core.** `MemoryBank(retention: MemoryRetention = …)` carries the
four strategies above as a `sealed interface` — `Sliding(maxLines)`, `TokenBudget(maxTokens, estimateTokens)`,
`Summarized(keepRecentLines, summarize)`, `Unbounded` — applied on every write. The historical `maxLines`
cap is `Sliding`, and remains the default, so existing banks are unchanged. What's still *planned* is the
**typed multi-namespace DSL** shown above (`memory { sliding<ConversationTurn>(20) }`) — per-type budgets
in one bank — which builds on this core. See `docs/memory.md` for the shipped API.

Memory is optional. Short-lived pipeline stages (parsers, formatters, validators) are stateless. Memory is for agents that improve with experience: reviewers, planners, domain experts.

### Fibonacci — Canonical Memory Test

Fibonacci is the classic training dummy for agentic memory. A single agent with no custom tools — just `memory_read`, `memory_write`, and a system prompt — demonstrates the full memory lifecycle: read state, compute, persist, return.

```kotlin
val bank = MemoryBank()

val fib = agent<String, Int>("fibonacci") {
    prompt("""You maintain a Fibonacci sequence in memory.
Memory format: "prev|curr". Empty memory means start fresh.

1. Call memory_read
2. If empty → answer=1, write "0|1"
   If "A|B" → answer=A+B, write "B|A+B"
3. Call memory_write with the new state
4. Reply with ONLY the answer number""")
    memory(bank)
    model { ollama("llama3"); temperature = 0.0 }
    skills { skill<String, Int>("fib", "Generate next Fibonacci number") {
        tools()   // memory_read and memory_write are auto-available
        parseOutput { it.trim().toInt() }
    }}
}

fib("do it")  // → 1   (bank: "0|1")
fib("do it")  // → 1   (bank: "1|1")
fib("do it")  // → 2   (bank: "1|2")
fib("do it")  // → 3   (bank: "2|3")
fib("do it")  // → 5   (bank: "3|5")

// Pre-seed to resume from any point:
bank.write("fibonacci", "21|34")
fib("do it")  // → 55  (bank: "34|55")
```

This tests three properties at once: that memory persists across invocations, that the agent correctly reads and writes state, and that the LLM can follow a stateful algorithm using only tool calls and prompt instructions. If Fibonacci works, the memory system is sound.

### 8.6 Prompt Model: Typed Public Interface

#### 8.6.1 The Problem

Without a formal prompt entity, agents accept arbitrary untyped requests. A `coder` agent can receive "book me a flight" — the mismatch is discovered only after the LLM burns tokens attempting to route or hallucinate. In multi-agent systems this is worse: a manager agent can accidentally delegate to the wrong specialist, and the error surfaces as a bad output three hops downstream, not as a compile-time rejection at the delegation point.

The current model conflates two concerns:

- **Public interface** — what requests an agent accepts (currently: the agent's `IN` type + freeform strings)
- **Internal routing** — how the agent picks a skill (currently: `skill.description` + `RoutingStrategy`)

`Skill.description` serves double duty as both the public advertisement and the internal routing hint. This means the outside world must understand the agent's internal skill decomposition to use it correctly — a layering violation.

#### 8.6.2 Design: `Prompt<IN, OUT>`

A `Prompt` is a **typed, parameterized, reusable interaction template** — the agent's public API contract. It declares what the agent can be asked, how the request is structured for the LLM, and what knowledge slots the interaction requires.

```kotlin
@JvmInline
value class PromptId(val value: String)

class Prompt<IN, OUT>(
    val id: PromptId,
    val name: String,
    val description: String,                    // "sells" the prompt to callers and A2A discovery
    val inputType: KType,                       // reified from generic
    val outputType: KType,                      // reified from generic
    val template: PromptTemplate<IN>,           // message construction logic
    val expects: Set<KnowledgeSlot>,            // knowledge slots this prompt requires
    val outputFormat: OutputFormat<OUT>,         // structured, freeform, or schema-constrained
    val examples: List<PromptExample<IN, OUT>>, // few-shot pairs for the prompt itself
    val tags: Set<String>,                      // for discovery and filtering
)
```

#### 8.6.3 DSL Syntax

**Standalone prompt definition** — reusable across agents:

```kotlin
val securityAudit = prompt<CodeBundle, SecurityReport>("security-audit") {
    description("Analyzes code for OWASP Top 10 vulnerabilities and produces a severity-ranked report")

    template { input ->
        system("You are a senior security auditor. Language: ${input.language}.")
        user("Audit the following code for security vulnerabilities:\n${input.source}")
        assistant("I'll analyze this code against OWASP Top 10 categories...")  // prefill
    }

    expects {
        knowledge("owasp-checklist")      // slot name — skill must fill it
        knowledge("cve-database")         // optional slot
    }

    outputFormat { structured<SecurityReport>() }

    example(
        input = CodeBundle(language = "kotlin", source = "val db = DriverManager.getConnection(url, user, password)"),
        output = SecurityReport(
            findings = listOf(Finding(severity = CRITICAL, category = "A03:Injection", detail = "Hardcoded credentials")),
            score = 2.1
        )
    )

    tags("security", "owasp", "audit")
}
```

**Inline prompt definition** — when reuse is not needed:

```kotlin
val coder = agent<Specification, CodeBundle>("coder") {
    prompts {
        prompt<Specification, CodeBundle>("write-code") {
            description("Generates production Kotlin code from a specification")
            template { spec ->
                system("You are an expert Kotlin developer. Prefer immutability and coroutines.")
                user("Implement this specification:\n${spec.toMarkdown()}")
            }
            expects { knowledge("style-guide") }
            outputFormat { structured<CodeBundle>() }
        }

        prompt<RefactorRequest, CodeBundle>("refactor") {
            description("Refactors existing code to meet a new specification without breaking contracts")
            template { req ->
                system("You are a senior Kotlin developer specializing in safe refactoring.")
                user("Original code:\n${req.existingCode}\n\nNew requirements:\n${req.newSpec}")
            }
            expects { knowledge("style-guide"); knowledge("refactor-rules") }
            outputFormat { structured<CodeBundle>() }
        }

        // This agent does NOT accept FlightRequest.
        // Any attempt to route FlightRequest here → compile error.
    }
}
```

#### 8.6.4 Prompt–Skill Relationship

Prompts are **public**; skills are **private**. The prompt defines *what you can ask*; the skill defines *how it gets done*.

```
External caller / Manager agent
        │
        ▼
    ┌─────────────────────────────────────┐
    │ Agent<Specification, CodeBundle>     │
    │                                     │
    │   Prompts (public interface):       │
    │     • write-code                    │ ◄── Discoverable via A2A / MCP
    │     • refactor                      │
    │                                     │
    │   ┌─────────────────────────────┐   │
    │   │ Skills (private impl):      │   │
    │   │   • greenfield-code         │   │ ◄── Internal only
    │   │   • incremental-code        │   │
    │   │   • format-code (utility)   │   │
    │   └─────────────────────────────┘   │
    │                                     │
    │   Prompt → Skill routing:           │
    │     write-code ──┬── greenfield     │
    │                  └── incremental    │
    │     refactor ────── incremental     │
    └─────────────────────────────────────┘
```

**Routing from prompt to skill:**

```kotlin
val coder = agent<Specification, CodeBundle>("coder") {
    prompts {
        +writeCode        // Prompt<Specification, CodeBundle>
        +refactor         // Prompt<RefactorRequest, CodeBundle>
    }

    skills {
        skill<Specification, CodeBundle>("greenfield-code") {
            triggeredBy { prompt(writeCode) where { !input.hasExistingCode } }
            knowledge("style-guide") { "Prefer val over var..." }
            implementedBy { tools("write_file", "compile") }
        }

        skill<Specification, CodeBundle>("incremental-code") {
            triggeredBy {
                prompt(writeCode) where { input.hasExistingCode }
                prompt(refactor)  // also reachable from refactor prompt
            }
            knowledge("style-guide") { "Prefer val over var..." }
            knowledge("refactor-rules") { "Preserve public API surface..." }
            implementedBy { tools("read_file", "edit_file", "compile") }
        }
    }
}
```

**Key constraint:** Every prompt's `expects {}` knowledge slots must be satisfied by at least one skill reachable from that prompt. The compiler validates this:

```
ERROR [Prompt:30]: Prompt "security-audit" expects knowledge slot "cve-database"
   but no skill triggered by this prompt provides it.
   Skills triggered: [greenfield-code, incremental-code]
   Available slots: [style-guide, refactor-rules]
   Missing: [cve-database]
```

#### 8.6.5 Type-Safe Request Routing

The core value proposition: **callers cannot address requests to wrong agents.**

**Agent-to-agent delegation (compile-time):**

```kotlin
val manager = agent<ProjectRequest, DeliveryPackage>("project-manager") {
    skills {
        skill<ProjectRequest, DeliveryPackage>("deliver") {
            implementedBy {
                pipeline {
                    // specMaster exposes prompt accepting TaskRequest
                    invoke(specMaster, specMaster.prompt("create-spec"))

                    // coder exposes prompt accepting Specification
                    invoke(coder, coder.prompt("write-code"))

                    // COMPILE ERROR: coder has no prompt accepting TaskRequest
                    invoke(coder, coder.prompt("create-spec"))
                    //     Available prompts on "coder": write-code(Specification), refactor(RefactorRequest)
                    //     None accept: TaskRequest

                    // COMPILE ERROR: reviewer has no prompt accepting Specification
                    invoke(reviewer, reviewer.prompt("write-code"))
                    //     "reviewer" does not expose prompt "write-code".
                    //     Available prompts on "reviewer": review-code(CodeBundle)
                }
            }
        }
    }
}
```

**A2A remote invocation (runtime with schema validation):**

```kotlin
// External caller discovers prompts via AgentCard
val card = discoverAgent("https://api.deep-code.ai/.well-known/agent.json")

// Runtime validation: does the remote agent accept this request shape?
val prompt = card.prompts.find { it.name == "write-code" }
    ?: error("Agent does not expose 'write-code' prompt")

// Schema check: does my input match the prompt's inputSchema?
prompt.validateInput(mySpecification)  // throws if schema mismatch
val result = card.invoke(prompt, mySpecification)
```

**CLI invocation (discoverable command set):**

```bash
# List available prompts on an agent
$ agents prompts coder
  write-code    Generates production Kotlin code from a specification
                Input:  Specification
                Output: CodeBundle

  refactor      Refactors existing code to meet a new specification
                Input:  RefactorRequest
                Output: CodeBundle

# Invoke a specific prompt
$ agents invoke coder --prompt write-code --input ./api-spec.yaml

# Error: agent "coder" does not expose prompt "deploy"
$ agents invoke coder --prompt deploy --input ./config.yaml
```

#### 8.6.6 Knowledge Slot Binding

Prompts declare **what knowledge they need** (slots); skills provide **what knowledge they have** (entries). The framework validates the binding.

```kotlin
// Prompt declares slots
val securityAudit = prompt<CodeBundle, SecurityReport>("security-audit") {
    expects {
        knowledge("owasp-checklist")                    // required slot
        knowledge("cve-database", required = false)     // optional slot
    }
}

// Skill fills slots
skill<CodeBundle, SecurityReport>("audit") {
    triggeredBy { prompt(securityAudit) }

    knowledge("owasp-checklist") { loadChecklist("security/owasp-2025.md") }
    knowledge("cve-database") { queryCveApi(input.language) }

    implementedBy { tools("static_analysis", "report_generator") }
}
```

**Delivery to the LLM:** When a prompt is invoked, the framework:

1. Selects the skill via `triggeredBy` routing
2. Evaluates the prompt's `template {}` to construct messages
3. Resolves knowledge slots from the skill's `knowledge {}` entries
4. Injects knowledge into the LLM context using the skill's chosen model (all-at-once or tools)
5. Validates the output against the prompt's `outputFormat`

This means the prompt controls the **conversation structure** (system/user/assistant messages) while the skill controls the **knowledge and execution strategy**. The same prompt with different skills produces different results because the knowledge is different — but the interaction pattern is consistent.

#### 8.6.7 Prompt Template DSL

The template DSL constructs a typed message sequence:

```kotlin
sealed interface PromptMessage {
    data class System(val content: String) : PromptMessage
    data class User(val content: String) : PromptMessage
    data class Assistant(val content: String) : PromptMessage  // prefill
}

class PromptTemplate<IN>(
    val build: (IN) -> List<PromptMessage>
)

// DSL builder
template { input: CodeBundle ->
    system("You are a security auditor specializing in ${input.language}.")
    user("Audit the following code:\n${input.source}")
    assistant("I'll analyze this against OWASP Top 10...")  // optional prefill
}
```

**Parameterized templates** — the template receives the typed input and can use any field:

```kotlin
prompt<TranslationRequest, TranslatedDoc>("translate") {
    template { req ->
        system("You are a professional translator. Source: ${req.sourceLang}. Target: ${req.targetLang}.")
        user(req.content)
    }
}
```

**Multi-turn templates** — for prompts that require structured multi-step interaction:

```kotlin
prompt<DesignRequest, DesignDoc>("design-review") {
    template { req ->
        system("You are a senior architect reviewing a system design.")
        user("Here is the design document:\n${req.document}")
        assistant("I'll review this against the following criteria...")
        user("Focus on: ${req.focusAreas.joinToString()}")
    }
}
```

#### 8.6.8 Output Format

Prompts declare how the output should be structured:

```kotlin
sealed interface OutputFormat<OUT> {
    class Structured<OUT>(val schema: KType) : OutputFormat<OUT>    // JSON schema from data class
    class Freeform<OUT>(val parser: (String) -> OUT) : OutputFormat<OUT>  // custom parsing
    class SchemaConstrained<OUT>(val jsonSchema: JsonObject) : OutputFormat<OUT>  // explicit JSON Schema
}

// DSL
outputFormat { structured<SecurityReport>() }           // auto-generates JSON Schema from data class
outputFormat { freeform { raw -> parseMarkdown(raw) } } // custom parser
outputFormat { schema(securityReportSchema) }            // explicit schema
```

When `structured` is used, the framework auto-generates a JSON Schema from the Kotlin data class and passes it to the LLM as a response format constraint. This maps directly to MCP's `outputSchema` on tools.

#### 8.6.9 Serialization

**agent.json — Prompt Section:**

```json
{
  "spec": {
    "prompts": [
      {
        "id": "write-code",
        "name": "Write Code",
        "description": "Generates production Kotlin code from a specification",
        "inputSchema": {
          "type": "object",
          "properties": {
            "language": { "type": "string" },
            "requirements": { "type": "array", "items": { "type": "string" } },
            "hasExistingCode": { "type": "boolean" }
          },
          "required": ["language", "requirements"]
        },
        "outputSchema": {
          "type": "object",
          "properties": {
            "files": { "type": "array" },
            "compilable": { "type": "boolean" }
          }
        },
        "expects": [
          { "slot": "style-guide", "required": true },
          { "slot": "cve-database", "required": false }
        ],
        "examples": [
          {
            "input": { "language": "kotlin", "requirements": ["REST API", "CRUD"] },
            "output": { "files": ["UserController.kt"], "compilable": true }
          }
        ],
        "tags": ["kotlin", "generation"]
      }
    ],
    "skills": [ ... ],
    "types": { ... }
  }
}
```

**A2A AgentCard — Prompt Mapping:**

| Prompt DSL | A2A AgentCard | Exported? |
|------------|---------------|-----------|
| `prompt.name` | `skills[].name` | Yes — prompts map to A2A skills |
| `prompt.description` | `skills[].description` | Yes |
| `prompt.inputSchema` | `skills[].inputModes` | Yes — JSON Schema advertised |
| `prompt.outputSchema` | `skills[].outputModes` | Yes |
| `prompt.tags` | `skills[].tags` | Yes |
| `prompt.examples` | — | Not in A2A spec; available via agent.json |
| `prompt.expects` | — | Internal: knowledge wiring |
| `prompt.template` | — | Internal: LLM interaction pattern |

**Key mapping decision:** A2A's `skills[]` are the public interface of an agent — they describe *what the agent can do*. This maps to **prompts**, not to Agents.KT skills. The A2A skill list is generated from the agent's `prompts {}` block, not from `skills {}`. This maintains the public/private separation: external callers see prompts; internal implementation (skills) remains opaque.

```kotlin
// Before (v0.9): AgentCard.skills generated from agent's skills
// After (v1.0): AgentCard.skills generated from agent's prompts

val card = coder.toAgentCard(...)
// card.skills = [
//   { name: "write-code", description: "Generates production Kotlin code...", inputModes: [...] },
//   { name: "refactor", description: "Refactors existing code...", inputModes: [...] }
// ]
// NOT: [{ name: "greenfield-code", ... }, { name: "incremental-code", ... }]
```

**MCP Prompt Compatibility:**

When an Agents.KT agent acts as an MCP server (Phase 3), its prompts are exposed via MCP's `prompts/list` and `prompts/get`:

```json
// MCP prompts/list response (auto-generated from agent's prompts {})
{
  "prompts": [
    {
      "name": "write-code",
      "description": "Generates production Kotlin code from a specification",
      "arguments": [
        { "name": "language", "description": "Target programming language", "required": true },
        { "name": "requirements", "description": "List of requirements", "required": true }
      ]
    }
  ]
}
```

```json
// MCP prompts/get response (template evaluated with arguments)
{
  "messages": [
    { "role": "system", "content": "You are an expert Kotlin developer..." },
    { "role": "user", "content": "Implement this specification:\n..." }
  ]
}
```

#### 8.6.10 Prompt Composition

Prompts can be composed, just like agents:

**Prompt chaining** — one prompt's output feeds another:

```kotlin
// The framework validates: writeCode.OUT == reviewCode.IN
val writeAndReview = writeCode then reviewCode
// Result: ComposedPrompt<Specification, ReviewResult>
```

**Prompt refinement** — extending a base prompt with additional constraints:

```kotlin
val strictWriteCode = writeCode.refine {
    template { input ->
        inherit()  // include base template messages
        user("Additional constraint: 100% test coverage required.")
    }
    expects {
        inherit()  // include base knowledge slots
        knowledge("coverage-rules")  // add new slot
    }
}
```

**Prompt variants** — same interface, different interaction patterns:

```kotlin
val verboseAudit = securityAudit.variant("verbose") {
    template { input ->
        system("You are a security auditor. Explain each finding in detail with remediation steps.")
        user("Audit this code:\n${input.source}")
    }
}

val conciseAudit = securityAudit.variant("concise") {
    template { input ->
        system("You are a security auditor. List findings as one-liners, severity only.")
        user(input.source)
    }
}
```

#### 8.6.11 Compile-Time Validations

New validations added to the catalog:

| # | Category | Check | Severity |
|---|----------|-------|----------|
| 27 | **Prompts** | Every exposed prompt must be handled by at least one skill (`triggeredBy`) | Error |
| 28 | **Prompts** | Prompt input type must be assignable from agent's `IN` type (or a subtype the agent can construct) | Error |
| 29 | **Prompts** | Prompt output type must be assignable to agent's `OUT` type | Error |
| 30 | **Prompts** | Every `expects` required knowledge slot must be provided by all skills reachable from that prompt | Error |
| 31 | **Prompts** | Prompt `outputFormat` structured type must match prompt's `OUT` type parameter | Error |
| 32 | **Prompts** | No two prompts on the same agent may have identical `(inputType, name)` pairs | Error |
| 33 | **Prompts** | Agent with `prompts {}` block must have at least one prompt | Error |
| 34 | **Prompts** | Optional knowledge slot in `expects` should be provided by at least one skill (otherwise the slot is dead code) | Warning |

**Error message examples:**

```
ERROR [Prompt:27]: Prompt "security-audit" on agent "coder" is not
   handled by any skill. Add `triggeredBy { prompt(securityAudit) }`
   to at least one skill.

ERROR [Prompt:28]: Prompt "write-code" accepts Specification
   but agent "reviewer" consumes CodeBundle.
   Prompt input type must be assignable from the agent's IN type.

ERROR [Prompt:30]: Prompt "security-audit" expects required knowledge
   slot "owasp-checklist" but skill "greenfield-code" (triggered by this
   prompt) does not provide it.
   Provided by skill: [style-guide]
   Missing: [owasp-checklist]

WARNING [Prompt:34]: Prompt "security-audit" declares optional knowledge
   slot "threat-model" but no reachable skill provides it.
   Consider removing the slot or adding knowledge to a skill.
```

#### 8.6.12 Backward Compatibility

The prompt entity is **additive** — existing agents without `prompts {}` blocks continue to work as before:

- **No `prompts {}` block:** Agent accepts its `IN` type directly. Skills use `routing {}` or `RoutingStrategy.LLM_DECISION` as currently specified. The agent's A2A card generates skills from `skills {}` (existing behavior).
- **With `prompts {}` block:** Agent accepts requests only through declared prompts. Skills use `triggeredBy { prompt(...) }` for routing. The agent's A2A card generates skills from `prompts {}` (new behavior). Any skill without a `triggeredBy` clause becomes an internal utility skill — not reachable from external prompts, only callable by other skills or by the agent's own LLM routing.

**Migration path:**

```kotlin
// Before: skill description is the public interface
val coder = agent<Specification, CodeBundle>("coder") {
    skills {
        skill<Specification, CodeBundle>("write-code", "Generates Kotlin code...") {
            implementedBy { tools("write_file", "compile") }
        }
    }
}

// After: prompt is the public interface, skill is internal
val coder = agent<Specification, CodeBundle>("coder") {
    prompts {
        prompt<Specification, CodeBundle>("write-code") {
            description("Generates production Kotlin code from a specification")
            template { spec -> ... }
        }
    }
    skills {
        skill<Specification, CodeBundle>("write-code-impl") {
            triggeredBy { prompt("write-code") }
            knowledge("style-guide") { ... }
            implementedBy { tools("write_file", "compile") }
        }
    }
}
```

#### 8.6.13 Shared Prompt Libraries

Prompts are standalone typed values — they can be packaged and distributed like knowledge packs:

```kotlin
// Published as: dev.agentskt.prompts:security-prompts:1.0.0
object SecurityPrompts {
    val audit = prompt<CodeBundle, SecurityReport>("security-audit") { ... }
    val penTest = prompt<Endpoint, PenTestReport>("pen-test") { ... }
    val compliance = prompt<CodeBundle, ComplianceReport>("compliance-check") { ... }
}

// Consumed by any agent:
val securityReviewer = agent<CodeBundle, SecurityReport>("security-reviewer") {
    prompts {
        +SecurityPrompts.audit
        +SecurityPrompts.compliance
    }
    skills { ... }
}
```

**Distribution format:** Prompt libraries are published as Maven artifacts containing:

```
security-prompts-1.0.0.jar
├── META-INF/
│   └── prompts/
│       ├── security-audit.json       ← serialized prompt definition
│       ├── pen-test.json
│       └── compliance-check.json
├── com/deepcode/prompts/security/
│   └── SecurityPrompts.class         ← compiled prompt objects
└── examples/
    └── audit-examples.json           ← bundled few-shot pairs
```

#### 8.6.14 UML Mapping

| DSL Concept | UML Equivalent |
|-------------|---------------|
| `prompt<IN,OUT>` | Provided interface (port on component boundary) |
| `prompt.template` | Interaction diagram / protocol state machine |
| `prompt.expects` | Required interface (dependency on knowledge provider) |
| `prompt → skill` routing | Realize relationship (interface → implementation) |

Addition to the existing UML Isomorphism table:

```
Bidirectional: Prompt ←→ UML Provided Interface
  Draw UML provided interface → generate prompt stub
  Write prompt DSL → visualize as port on component boundary
```

#### 8.6.15 Roadmap Placement

**Phase 2 (Q2 2026):**
- `Prompt<IN, OUT>` entity definition and DSL
- Prompt → Skill routing via `triggeredBy`
- Knowledge slot binding and validation
- Compile-time validations #27–#34
- Prompt serialization in agent.json
- A2A AgentCard generation from prompts (replacing skill-based generation)
- CLI: `agents prompts <agent>` command

**Phase 3 (Q3 2026):**
- MCP prompt compatibility (`prompts/list`, `prompts/get`)
- Prompt composition operators (`then`, `refine`, `variant`)
- Shared prompt libraries (Maven distribution)
- AgentUnit: prompt-level testing (validate template output, knowledge slot resolution)

#### 8.6.16 Open Questions

11. **Prompt variance:** Should `Prompt<IN, OUT>` support contravariance on IN? A `Prompt<Animal, Report>` could accept `Dog` — useful for generic prompts consumed by specialized agents.

12. **Prompt versioning:** When a prompt's schema changes (new required field), how do existing callers discover the breaking change? Semantic versioning on prompt definitions? Deprecation annotations?

13. **Dynamic prompt discovery:** Should agents be able to register prompts at runtime (e.g., an agent that generates prompts from a database schema)? This conflicts with compile-time validation but enables meta-agent patterns.

14. **Prompt-level budgets:** Should prompts carry token budget hints? A "concise audit" prompt might have a lower budget than a "verbose audit" — useful for cost management in production.

---

## 9. Two-Layer Architecture

### 9.1 Layer 1: Agent Definition (Free)

```kotlin
val specMaster = agent<TaskRequest, Specification>("spec-master") {
    description = "Specification author and guardian"
    version = "1.0.0"

    skills {
        skill("create-spec") {
            name = "Create Specification"
            description = "Creates technical specifications from requirements"
            tags("specs", "documentation", "openapi")
            examples("create REST API spec", "write OpenAPI doc")
            inputModes("text/plain", "application/json")
            outputModes("application/json", "text/markdown")

            knowledge("instructions", "How to create specifications") {
                loadFile("specs/create-spec.md")
            }
            knowledge("conventions", "OpenAPI conventions and standards") {
                loadFile("specs/openapi-conventions.md")
            }

            implementedBy { tools("create_spec", "search_spec") }
        }
    }

    tools {
        tool("create_spec") {
            param("title", STRING) { required() }
            param("format", STRING) { enum("openapi", "uml"); default("openapi") }
            returns(SPEC_REF)
        }
        tool("search_spec") {
            param("query", STRING) { required() }
            returns(LIST(SPEC_REF))
        }
    }

    capabilities { streaming = true; pushNotifications = false }
    defaultInputModes("text/plain", "application/json")
    defaultOutputModes("application/json")
    requires { tools(createSpec, searchSpec) }  // agent declares which tools it needs
    model { title = "qwen-2.5-coder"; temperature = 0.2 }
}
```

### 9.2 Layer 2: Structure DSL (Strict)

Permissions are **tool grants** — not magic strings. If a tool isn't granted, it doesn't exist for that agent. No routing block — use `branch {}` on a sealed input type (§7.7) for dispatch.

```kotlin
structure("deep-code") {
    root(project) {
        grants { tools(*) }  // root has access to all tools
        budget { maxTokens = 500_000; maxTime = 30.minutes }

        delegates(specMaster) {
            grants { tools(createSpec, searchSpec, drawUml) }
            delegates(umlDrawer) { grants { tools(drawUml) } }
        }

        delegates(codeMaster) {
            grants { tools(writeFile, compile, readFile, editFile) }
            delegates(coder)    { grants { tools(writeFile, compile) } }
            delegates(tester)   { grants { tools(readFile, runTests) } }
            delegates(reviewer) { grants { tools(readFile, lint) } }
        }
    }
}
```

The `grants` block takes actual `Tool<*,*>` references. Construction-time validation checks: every tool referenced in a skill's `implementedBy { tools(...) }` must be in the agent's granted set. Parent's grants must be a superset of child's grants.


### 9.2.1 Runtime Tool Confirmation

Every tool has one of three runtime states — convention over configuration:

```kotlin
delegates(deployer) {
    grants {
        tools(dockerBuild, readFile)        // granted — auto-approved, no confirmation
        confirm(kubectlApply, kubectlDelete) // confirmed — user approves before execution
    }
    // Tools NOT listed → don't exist for this agent. No deny() needed.
}
```

| State | Meaning |
|-------|---------|
| **Granted** | Tool auto-approved. No user interaction. |
| **Confirmed** | Agentic loop pauses. User approves/rejects. |
| **Absent** | Tool doesn't exist for this agent. LLM never sees it. |

Three states, not five modes. If you didn't grant it, it doesn't exist.

**Human-in-the-loop** — `confirm()` supports message templates, timeouts, and fallback behavior:

```kotlin
delegates(deployer) {
    grants {
        tools(dockerBuild)
        confirm(kubectlApply) {
            message  = "Agent wants to deploy {image} to {namespace}"  // template from tool args
            timeout  = 5.minutes                                       // auto-reject after timeout
            fallback = ToolConstraint.Forbidden                        // what happens on timeout/rejection
        }
        confirm(deleteDatabase) {
            message  = "⚠️ Agent wants to DELETE database {name}. This is irreversible."
            timeout  = 10.minutes
            fallback = ToolConstraint.Forbidden
        }
    }
}
```

This is the runtime complement to compile-time tool grants — `grants {}` controls what tools *exist*, `confirm()` controls which ones need a human gate before execution. In headless/CI mode, `confirm()` tools auto-approve (or fail — configurable per deployment).

### 9.2.2 Team Coordination (Future)

Team coordination (multiple agents running concurrently with async message passing) is deferred. In practice, team/swarm patterns cause Gradle to hang and resource exhaustion on current hardware.

The framework provides the building blocks — `AgentSession` (§5.7), `.spawn {}` (§10.1), agent memory (§8.5), hooks (§8.4) — for developers to compose team-like patterns in application code using standard Kotlin coroutines. If a reusable pattern emerges from real usage, it will be codified as a DSL.

### 9.3 Layer Separation

| Aspect | Layer 1: Definition | Layer 2: Structure |
|--------|--------------------|--------------------|
| Purpose | WHAT an agent does and knows | WHO manages whom, with what authority |
| DSL entry | `agent<IN,OUT>("name") { }` | `structure("name") { root { delegates { } } }` |
| Constraints | Only type contract (IN/OUT) | Tool grants + budget + delegation topology |
| Analogy | Employee resume + training manual | Org chart + HR approval |

---

## 10. Composition Operators

| Operator | Semantics | Type Constraint | Result Type |
|----------|-----------|----------------|-------------|
| `then` | Sequential pipeline | `A.OUT == B.IN` | `Pipeline<A.IN, B.OUT>` |
| `*` | Forum shorthand (participants + captain) | Shared `IN`, last's `OUT` (captain) | `Forum<IN, last.OUT>` |
| `/` | Parallel (fan-out) | All share `IN` and `OUT` (or common supertype via Liskov) | `Parallel<IN, OUT>` — next stage receives `List<OUT>` |
| `.loop {}` | Iterative — `null` stops, `IN` continues | `(OUT) -> IN?` feedback block | `Loop<IN, OUT>` — composable with `then` |
| `>>` | Security wrap | `Guard<IN,IN> >> Pipeline<IN,OUT>` | `Pipeline<IN, OUT>` |
| `>>` | Educate-then-execute | Educator injects knowledge | `Pipeline<IN, OUT>` |
| `.branch {}` | Conditional routing on sealed OUT | All variants → same `OUT` type; unhandled variant throws at invocation | `Branch<IN, OUT>` — composable with `then` |
| `.spawn {}` | Independent sub-agent lifecycle | Parent holds `AgentHandle<OUT>` | `AgentHandle<OUT>` — parent-managed join point |
| `.with {}` | Config override | Same types | `Agent<IN, OUT>` |

### 10.1 Spawn: Independent Sub-Agent Lifecycle

Unlike `then` (synchronous handoff) or `/` (parallel fan-out with auto-join), `.spawn {}` creates a detached execution the parent can check on, await, or cancel:

```kotlin
val handle: AgentHandle<AnalysisResult> = explore.spawn(request) {
    background = true
    timeout    = 10.minutes
    onComplete { result -> log("Explore finished: $result") }
}

// Parent continues working...
val otherResult = coder(spec)

// Join later
val analysis = when {
    handle.isComplete -> handle.result
    else              -> handle.await()
}

// Fan-out with independent lifecycles (different from / which auto-joins)
val handles = tasks.map { task -> worker.spawn(task) { background = true } }
val results = handles.awaitAll()
```

| | `/` (Parallel) | `.spawn {}` |
|---|---|---|
| Lifecycle | Framework-managed, auto-joined | Parent-managed, explicit join |
| Blocking | Next stage waits for all | Parent continues immediately |
| Error handling | Any failure fails the parallel | Parent decides per-handle |
| Use case | Fan-out → merge | Background tasks, async workers |

### 10.2 Pipeline Observability

Pipelines emit a sealed event hierarchy. Every event carries `timestamp` and `agentName`.

```kotlin
sealed interface PipelineEvent {
    val timestamp: Instant
    val agentName: String

    // ─── Pipeline lifecycle ───
    data class StageStarted(/*...*/ val stageIndex: Int) : PipelineEvent
    data class StageCompleted(/*...*/ val duration: Duration, val tokenUsage: TokenUsage?) : PipelineEvent
    data class PipelineCompleted(/*...*/ val totalDuration: Duration, val totalTokens: Int) : PipelineEvent
    data class PipelineFailed(/*...*/ val error: Throwable, val failedAtStage: Int) : PipelineEvent

    // ─── Agentic loop internals ───
    data class InferenceStarted(/*...*/ val turn: Int) : PipelineEvent
    data class InferenceCompleted(/*...*/ val turn: Int, val finishReason: FinishReason) : PipelineEvent
    data class ToolCallStarted(/*...*/ val toolName: String, val arguments: JsonObject) : PipelineEvent
    data class ToolCallCompleted(/*...*/ val toolName: String, val duration: Duration) : PipelineEvent
    data class ToolCallFailed(/*...*/ val toolName: String, val error: Throwable) : PipelineEvent

    // ─── Skill & knowledge ───
    data class SkillChosen(/*...*/ val skillName: String) : PipelineEvent
    data class KnowledgeLoaded(/*...*/ val entryName: String, val contentLength: Int) : PipelineEvent

    // ─── Streaming ───
    data class TextDelta(/*...*/ val text: String) : PipelineEvent

    // ─── Budget ───
    data class BudgetWarning(/*...*/ val usedPercent: Double) : PipelineEvent
    data class BudgetExceeded(/*...*/ val limit: String) : PipelineEvent

    // ─── Sub-agent ───
    data class SubAgentSpawned(/*...*/ val childAgent: String) : PipelineEvent
    data class SubAgentCompleted(/*...*/ val childAgent: String, val duration: Duration) : PipelineEvent

    // ─── Session ───
    data class ContextCompacted(/*...*/ val beforeTokens: Int, val afterTokens: Int) : PipelineEvent
}
```

Observation via `Flow`:

```kotlin
val pipeline = parser then coder then reviewer

// Option A: observe + execute
pipeline.observe { event ->
    when (event) {
        is PipelineEvent.StageStarted     -> log("Starting ${event.agentName}")
        is PipelineEvent.StageCompleted   -> log("${event.agentName} done in ${event.duration}")
        is PipelineEvent.ToolCallStarted  -> showSpinner(event.toolName)
        is PipelineEvent.ToolCallFailed   -> showError(event.error)
        is PipelineEvent.SkillChosen      -> log("Selected skill: ${event.skillName}")
        is PipelineEvent.KnowledgeLoaded  -> log("Loaded: ${event.entryName}")
        is PipelineEvent.BudgetWarning    -> warn("${event.usedPercent}% budget used")
        is PipelineEvent.TextDelta        -> print(event.text)  // streaming output
        else -> { }
    }
}
val result = pipeline(input)

// Option B: stream as Flow (for reactive UIs)
pipeline.events(input).collect { event -> /* same when block */ }
// Cancelling the Flow cancels the running stage and all spawned sub-agents.
```

This event hierarchy is the telemetry backbone. The shipped `:agents-kt-otel` adapter maps runtime events to OTel spans with a nested hierarchy: `pipeline → stage → agent → skill → tool → llm_call`. Each span carries token usage and budget-attribution attributes across multi-agent pipelines.

### 10.3 Common Agent Patterns

The framework has no named "pattern" abstraction. Instead, well-known agent patterns emerge from composing existing primitives. This section maps academic and industry patterns to Agents.KT constructs.

#### ReAct (Reason + Act)

**What it is:** The agent reasons about the task, decides to call a tool, observes the result, and reasons again — repeating until it has a final answer.

**In Agents.KT:** This is the default agentic loop (§5.6). Every agent with `model {}` + `implementedBy { tools(...) }` runs ReAct automatically.

```kotlin
val researcher = agent<Question, Answer>("researcher") {
    model { ollama("qwen3:14b") }
    tools {
        +webSearch
        +readDocument
        +extractFacts
    }
    skills {
        skill<Question, Answer>("research", "Researches a question using available tools") {
            implementedBy { tools("web_search", "read_document", "extract_facts") }
        }
    }
}
// Execution: Reason → web_search() → Reason → read_document() → Reason → Answer
```

#### Reflection

**What it is:** An agent produces output, a critic evaluates it, and the producer revises based on feedback — repeating until quality is sufficient.

**In Agents.KT:** `then` + `.loop {}` on a sealed review result.

```kotlin
val reflectionLoop = (coder then reviewer).loop { result ->
    when (result) {
        is ReviewResult.Passed        -> null             // stop — quality met
        is ReviewResult.NeedsRevision -> result.feedback   // feed back for revision
        is ReviewResult.Failed        -> throw QualityException(result.issues)
    }
}

val pipeline = specMaster then reflectionLoop then deployer
// Pipeline<TaskRequest, DeployResult>
```

For self-reflection (same agent critiques its own output), use a single agent with two skills — one produces, one critiques — inside a loop:

```kotlin
val selfReflect = writer.loop { draft ->
    val critique = writer.skills["self-critique"]!!.execute(draft)
    if (critique.score >= 0.9) null else draft.reviseWith(critique)
}
```

#### Reflexion

**What it is:** Reflection + persistent memory. The agent remembers what failed across invocations and avoids repeating mistakes.

**In Agents.KT:** Reflection loop + agent memory (§8.5).

```kotlin
val reflexiveCoder = agent<Specification, CodeBundle>("reflexive-coder") {
    model { ollama("qwen3:14b") }

    memory {
        scope = MemoryScope.PROJECT
        file("past-failures.md")   // persists across invocations
        maxLines = 200
    }

    skills {
        skill<Specification, CodeBundle>("write-code", "Writes code, consulting past failures") {
            knowledge("failure-instructions") {
                "Before writing code, call memory_read to check past failures. " +
                "After a failed review, call memory_write to record what went wrong."
            }
            implementedBy { tools("write_file", "compile", "memory_read", "memory_write") }
        }
    }
}

// Wrap in reflection loop — failures get recorded in memory
val reflexionLoop = (reflexiveCoder then reviewer).loop { result ->
    when (result) {
        is ReviewResult.Passed        -> null
        is ReviewResult.NeedsRevision -> result.feedback
        is ReviewResult.Failed        -> result.issues.joinToString("\n")
    }
}
```

On each invocation, the agent reads `past-failures.md` and avoids known pitfalls. After a failed review, it writes the new failure to memory. Next invocation starts smarter.

#### Planning (Plan → Execute → Verify)

**What it is:** A meta-agent decomposes a task into steps, delegates each step, then verifies the aggregate result.

**In Agents.KT:** Manager agent with delegation knowledge (§8.3) + `pipeline` or `branch` inside `implementedBy`.

```kotlin
val planner = agent<TaskRequest, Plan>("planner") {
    model { ollama("qwen3:32b") }  // bigger model for planning
    skills {
        skill<TaskRequest, Plan>("decompose", "Breaks a task into ordered steps") {
            implementedBy { tools("analyze_requirements", "create_plan") }
        }
    }
}

val executor = agent<Plan, ExecutionResult>("executor") {
    skills {
        skill<Plan, ExecutionResult>("execute-plan", "Executes each step of a plan") {
            implementedBy { plan ->
                plan.steps.fold(ExecutionResult.empty()) { acc, step ->
                    acc + stepAgent(step)  // delegate each step
                }
            }
        }
    }
}

val verifier = agent<ExecutionResult, VerifiedResult>("verifier") { ... }

val planExecuteVerify = planner then executor then verifier
```

#### Expert Panel (Multi-Perspective)

**What it is:** Multiple specialist agents analyze the same input from different angles, then a synthesizer merges their perspectives.

**In Agents.KT:** `/` (parallel) + `then` synthesizer.

```kotlin
val securityReview  = agent<Code, Review>("security")  { /* security focus */ }
val styleReview     = agent<Code, Review>("style")     { /* style focus */ }
val perfReview      = agent<Code, Review>("perf")      { /* performance focus */ }

val panel = securityReview / styleReview / perfReview
// Parallel<Code, Review> → next stage gets List<Review>

val pipeline = coder then panel then synthesizer
// Pipeline<Spec, FinalReport>
```

For coordinated perspectives that should share the same input and converge on one result, use `*` (forum shorthand):

```kotlin
val debate = optimist * pessimist * realist * decisionMaker
// Forum<Proposal, Decision>
```

#### Hierarchical Delegation (Claude Code pattern)

**What it is:** A coordinator agent spawns specialized sub-agents, each with restricted tool access and their own context window.

**In Agents.KT:** `.asTool()` (§ discussed in spawn design) + Layer 2 `grants {}` for tool isolation.

```kotlin
val explore = agent<SearchRequest, Analysis>("explore") {
    model { ollama("qwen3:8b") }   // cheap model — read-only tasks
    tools { +glob; +grep; +readFile }
}

val coder = agent<TaskRequest, CodeBundle>("coder") {
    model { ollama("qwen3:14b") }
    tools {
        +writeFile; +compile
        +explore.asTool()   // sub-agent as a tool — own context, own budget
    }
    skills {
        skill<TaskRequest, CodeBundle>("implement", "Implements features") {
            implementedBy { tools("write_file", "compile", "explore") }
            // LLM calls "explore" like any tool → nested agentic loop runs
        }
    }
}
```

The explore agent literally cannot call `writeFile` — it's not in its tools. Isolation is structural, not prompts.

#### Pattern Summary

| Pattern | Primitives | Key Insight |
|---------|-----------|-------------|
| **ReAct** | `model {}` + `tools()` | Default agentic loop — no extra code |
| **Reflection** | `then` + `.loop {}` | Producer → Critic → loop until quality |
| **Reflexion** | Reflection + `memory {}` | Learns from failures across invocations |
| **Planning** | `then` pipeline with manager agent | Decompose → delegate → verify |
| **Expert Panel** | `/` parallel + synthesizer | Multiple perspectives, merged result |
| **Debate / jury** | `*` forum | Shared input, concurrent participants, captain/finalizer |
| **Hierarchical** | `.asTool()` + `grants {}` | Sub-agents with isolated tools and context |

The framework doesn't name these patterns — it provides the typed, validated building blocks. Patterns are how you compose them.

---

## 11. Validations

### 11.1 Validation Catalog

Validations are enforced at three levels: **compiler** (Kotlin generics — actual `kotlinc` errors), **construction-time** (eager checks when `agent<>()`, `then`, `*`, `/` are called — `IllegalArgumentException`), and **build-time** (Gradle `agentsValidate` task). The catalog marks each.

| # | Category | Check | Severity |
|---|----------|-------|----------|
| 1 | **Types** | `Agent<Any, Any>` forbidden — typed contract enforcement | Error |
| 2 | **Types** | Pipeline `then` requires `A.OUT == B.IN` | Error |
| 3 | **Types** | Forum `*` first's IN and last's OUT must match composition context | Error |
| 4 | **Types** | Branch must be exhaustive over sealed type | Error |
| 5 | **Types** | At least one skill must produce agent's `OUT` type | Error |
| 6 | **Types** | `implementedBy` must match skill's `<IN, OUT>` (not agent's) | Error |
| 7 | **Tools** | Skill's `implementedBy` tools ⊆ agent's granted tool set | Error |
| 8 | **Tools** | Agent's granted tools ⊆ parent's granted tools (monotonic) | Error |
| 9 | **Tools** | `confirm()` tools must also be in `grants` or `confirm` set | Error |
| 11 | **Topology** | No circular delegation | Error |
| 12 | **Topology** | Escalation targets exist as ancestors | Error |
| 13 | **Topology** | All defined agents placed in structure | Warning |
| 14 | **Skills** | `implementedBy.tools` exist in agent's `tools {}` | Error |
| 15 | **Skills** | `implementedBy.agent` type matches agent's contract | Error |
| 16 | **Skills** | `implementedBy.delegates` exist in structure | Error |
| 18 | **Skills** | Every skill has at least one `implementedBy` strategy | Error |
| 19 | **Knowledge** | Referenced knowledge files exist on disk | Error |
| 20 | **Knowledge** | Orphan knowledge files not referenced by any skill | Warning |
| 21 | **Knowledge** | Knowledge packs defined but never included | Warning |
| 22 | **Resources** | Child budgets ≤ parent budget | Warning |
| 23 | **Resources** | Forum participants ≤ concurrency limit | Warning |
| 24 | **Topology** | Agent instance placed in at most one structure (Pipeline or Forum) — cross-structure and duplicate reuse requires a new instance | Error |
| 25 | **Skills** | Skill descriptions are strongly recommended for routing quality | Warning |
| 26 | **Execution** | Agent invoked with no skills matching the required output type | Error |
| 27 | **Budget** | Agent with `model {}` must have explicit or inherited budget | Warning |
| 28 | **Budget** | Spawned agent budget ≤ parent remaining budget | Warning |
| 29 | **Session** | Agent with `session {}` config must have `model {}` | Error |
| 30 | **Hooks** | Hook references tool name that exists in agent's tool set | Error |
| 31 | **Memory** | Agent with `memory {}` has `memory_read`/`memory_write` in tools | Warning (auto-inject) |
| 34 | **MCP** | `Tool<IN, OUT>` with `@Generable` IN: generated `inputSchema` matches MCP server's schema | Warning |
| 35 | **MCP** | `Tool<IN, OUT>` with `@Generable` OUT: generated `outputSchema` matches MCP server's schema | Warning |
| 36 | **MCP** | MCP server tools referenced in `implementedBy { tools() }` exist at startup | Error |
| 37 | **MCP** | MCP server connection healthy on `agents serve` startup | Warning |
| 38 | **Constraints** | `constraints {}` references tool name that exists in skill's `tools()` | Error |
| 39 | **Constraints** | No contradictory constraints on same tool (e.g. `ForceAtStep` + `Forbidden`) | Error |
| 40 | **Constraints** | `onlyAfter()` prerequisites exist in skill's `tools()` | Error |

### 11.2 Error Message Examples

```
❌ ERROR [Type:1]: Agent "everything" uses <Any, Any>.
   Agent type parameters cannot be Any. Use specific types
   to enforce a typed contract.

❌ ERROR [Type:5]: Skill "write-and-test" pipeline produces CompiledCode
   but agent "coder" promises CodeBundle.
   Pipeline: writer(Spec→Raw) then compiler(Raw→Compiled)
   Missing final stage: Compiled → CodeBundle

❌ ERROR [Tools:7]: Skill "write-code" uses tool "write_file"
   but agent "coder" is not granted this tool.
   Granted tools: [readFile, glob, grep]
   Missing: [write_file]

❌ ERROR [Skill:15]: Skill "wrong-agent" delegates to agent "deployer"
   (CodeBundle→DeployResult) but must be (Specification→CodeBundle).

⚠️ WARNING [Topology:13]: Agent "logger" is defined but not placed
   in any structure.
```

---

## 12. Serialization and Distribution

### 12.1 Two Serialization Formats

```
Kotlin DSL (source of truth)
    │
    ├── agent.json    (descriptor: metadata + skills + types + permissions)
    │                  For inspection, A2A, catalogs, IDE support
    │
    ├── a2a-card.json (A2A AgentCard: public skills + capabilities)
    │                  For cross-system discovery
    │
    └── .jar          (executable bundle: .class + agent.json + knowledge)
                       For execution and distribution
```

### 12.2 agent.json

```json
{
  "$schema": "https://agentskt.dev/schema/agent/v0.5.json",
  "apiVersion": "agentskt/v0.5",
  "kind": "Agent",
  "metadata": {
    "name": "coder",
    "version": "2.1.0",
    "description": "Writes production Kotlin code from specifications"
  },
  "spec": {
    "types": {
      "consumes": "com.deepcode.types.Specification",
      "produces": "com.deepcode.types.CodeBundle"
    },
    "skills": [
      {
        "id": "write-code",
        "name": "Write Code",
        "description": "Generates Kotlin code from specs",
        "tags": ["kotlin", "generation"],
        "implementedBy": { "strategy": "tools", "tools": ["write_file", "compile"] }
      }
    ],
    "tools": [ ... ],
    "requires": { "permissions": ["code.write", "fs.write"] },
    "capabilities": { "streaming": true }
  }
}
```

**Shipped (#4516).** `Agent<*, *>.toAgentJson(version?, description?)` serializes the
definition deterministically (fixed key order → byte-stable output). The concrete shape tracks the sketch
above with two grounded adaptations: `apiVersion` is `agents-kt/v1`, and because `Agent<IN, OUT>` erases
its input type at runtime (only `outType: KClass` is retained), `spec.types.produces` is the agent's
output type while `spec.types.consumes` is the **list** of distinct skill input types. Each `spec.skills`
entry carries `name` / `description` / `consumes` / `produces`; each `spec.tools` entry carries `name` /
`description` / `risk`. This is the agent's portable *definition* — distinct from the permission
**manifest** (security/audit) and the A2A **AgentCard** (network discovery, §12.5).

### 12.3 Agent JAR Bundle

```
coder-2.1.0.jar
├── META-INF/
│   └── agents/
│       ├── agent.json              ← serialized definition
│       └── a2a-card.json           ← A2A AgentCard
├── knowledge/
│   └── code/
│       ├── write-kotlin.md         ← knowledge content files
│       └── kotlin-idioms.md        ← (developer-organized, no convention)
├── com/deepcode/agents/
│   ├── Coder.class                 ← compiled agent
│   └── tools/
│       ├── WriteFileTool.class     ← tool implementations
│       └── CompileTool.class
└── lib/                            ← dependencies (fat jar)
```

### 12.4 Three Bundle Types

| Bundle | Contains | Use Case |
|--------|----------|----------|
| **Agent Bundle** | Definition + tools + knowledge | Single agent distribution |
| **Team Bundle** | Structure + all agent bundles + shared packs | Complete system deployment |
| **Knowledge Pack** | Only .md files + pack definition | Shared knowledge across teams |

### 12.5 A2A AgentCard Auto-Generation

```kotlin
val card = specMaster.toAgentCard(
    url = "https://api.deep-code.ai/agents/spec-master",
    provider = Provider("K.Skobeltsyn Studio", "https://kskobeltsyn.ru"),
    protocolVersion = "0.3.0",
    authentication = Authentication.Bearer
)
```

**Field mapping: DSL → AgentCard:**

| Agent DSL | AgentCard | Exported? |
|-----------|-----------|-----------|
| `agent<IN,OUT>("name")` | `name` | ✓ |
| `description` | `description` | ✓ |
| `version` | `version` | ✓ |
| `skills { }` | `skills[]` | ✓ WHAT dimension only |
| `capabilities { }` | `capabilities` | ✓ |
| `defaultInputModes` | `defaultInputModes` | ✓ |
| `defaultOutputModes` | `defaultOutputModes` | ✓ |
| `<IN, OUT>` generics | — | Internal: pipeline type safety |
| `tools { }` | — | Internal: opaque to A2A |
| `knowledge { }` | — | Internal: agent's training |
| `requires { }` | — | Internal: structure validation |
| `implementedBy { }` | — | Internal: execution strategy |

Any node in the delegation tree can be exported as an A2A endpoint:

```kotlin
// External client sees one AgentCard with skill "Produce iPhone"
// Doesn't know 50 agents work behind it
project.toAgentCard(url = "https://api.deep-code.ai/agents/project")
```

### 12.6 AGNTCY Interoperability *(planned)*

[AGNTCY](https://github.com/agntcy) — the Linux Foundation "Internet of Agents" collective (Cisco/Outshift-led) — is the second cross-vendor interop stack alongside Google A2A (§12.5). Agents.KT targets **both**: A2A is the wire/invocation standard; AGNTCY adds a content-addressed **directory** and a **trust** layer. The native, typed `agent.json` (§12.2) stays the source of truth; AGNTCY support is a set of **exporters/clients over it**, exactly parallel to `toAgentCard()`.

**Strategic scoping (researched June 2026).** AGNTCY has five pillars; "full support" is narrower than it appears because two are out:

| Pillar | What | Status | Decision |
|--------|------|--------|----------|
| **OASF** | Agent *record* — discovery metadata (skills/domains as taxonomy IDs, locators, modules) | Live (`1.0.0`) | **Build** — export + import/validate |
| **DIR** | gRPC + OCI content-addressed *directory* (publish/discover by CID) | Live (`v1`) | **Build** — client (push/pull/search) |
| **Identity** | W3C VC "agent badges" (JOSE/JWKS) | Live, evolving | **Build verify/resolve** only (pure-JVM, cheap); defer issuance |
| **ACP** | REST runtime-invocation protocol | **Archived Apr 2026, merged into A2A** | **Do not build** — A2A (§12.5) subsumes it |
| **SLIM** | Rust MLS-encrypted transport beneath A2A/MCP | Live | **Defer** — only JVM binding is a JNI/JNA wrapper over a native lib; wrong shape for a pure-typed-JVM runtime |

So AGNTCY interop = **OASF + DIR + Identity-verify**, riding on the A2A we already do.

**OASF record export/import.** A third discovery exporter beside A2A:

```kotlin
val record = specMaster.toOasfRecord(
    version = "2.0.0",
    authors = listOf("K.Skobeltsyn <konstantin@skobeltsyn.com>"),
    locators = listOf(Locator.sourceCode("https://github.com/Deep-CodeAI/Agents.KT")),
)
// → OASF 1.0.0 JSON: name, version, schema_version, authors, created_at,
//   skills:[{name,id}], domains:[{name,id}], locators:[...], modules:[]
```

The one real engineering cost is the **skills/domains taxonomy**: OASF skills are not free text — each is `{name: "agent_orchestration/task_decomposition", id: 1001}`, where `id` is digit-concatenation of the hierarchy UIDs. No JVM SDK and no fuzzy matcher exist. Plan: **vendor** the `schema/skills` + `schema/domains` trees and compute IDs locally (offline, reproducible), with the hosted schema server (`schema.oasf.outshift.com/api/skills`) as a validation cross-check. Free-form agent skills map via an opt-in `.oasf("agent_orchestration/task_decomposition")` annotation; un-annotated skills export under a sensible default and a validation warning. Record **signing** (Sigstore/cosign over OCI) is external to the record JSON — a later optional integration, not part of the serializer.

**DIR client.** `buf generate buf.build/agntcy/dir` → grpc-kotlin stubs for `StoreService.{Push,Pull,Lookup}` (CID-addressed) and `RoutingService`/`SearchService`. DIR carries our OASF record as an opaque `google.protobuf.Struct`, so the JSON is enough — no OASF protos required. Auth is layered and optional (insecure dev / SPIFFE / OIDC bearer). Targets both self-hosted (`localhost:8888`) and the hosted network (`prod.api.ads.outshift.io`, auth-gated via hub login).

```kotlin
val dir = AgntcyDirectory.connect("localhost:8888")        // or hosted, with auth
val cid = dir.push(specMaster.toOasfRecord(...))           // → content id
val hits = dir.search(skill = "agent_orchestration/task_decomposition")
```

**Identity — verify/resolve.** Badge verification is pure-JVM and high-value for trust-gated networks: fetch `/.well-known/vcs.json` + `/.well-known/jwks.json` and validate the JOSE/JWS verifiable credential with an off-the-shelf JVM JWT library. Issuance (vault, key management, signing) is the heavy half and is deferred to the self-hosted stack.

**Deferred (documented, not built):** ACP REST adapter (only if forced to interop with already-deployed AGNTCY Workflow Servers), SLIM transport, OASF record signing + issuance, OASF modules (the standard module catalog is still empty in `1.0.0`).

Tracking: epic `[interop] AGNTCY support` with subtasks for OASF export, OASF import/validate, DIR client, and Identity verify.

### 12.7 AG-UI — Agent↔Frontend Serving *(planned, deferred)*

[AG-UI](https://github.com/ag-ui-protocol/ag-ui) (Agent-User Interaction Protocol) is the **agent↔user/frontend** layer of the interop stack. The standard framing — **MCP = agent↔tools, A2A = agent↔agent, AG-UI = agent↔user** — is stated by AG-UI's own docs, which note the three are complementary and often used together by one agent. It's the only interop layer that reaches an **end-user UI** (a streaming React/[CopilotKit](https://copilotkit.ai) chat surface) without us building a frontend.

**It is not another descriptor exporter.** `agent.json` (§12.2), the A2A AgentCard (§12.5), and the OASF record (§12.6) are *static descriptions* of an agent. AG-UI is a **runtime streaming surface**: a single `POST` of `RunAgentInput {threadId, runId, state, messages[], tools[], context[]}` that returns an **SSE stream of typed events**. It belongs in the serving layer — a direct bridge over the typed streaming `AgentSession` (§5.7), the runtime analog of how A2A pairs an AgentCard with a streaming server.

**The event surface maps ~1:1 onto `AgentSession`:**

| AG-UI event family | AgentSession |
|---|---|
| `RUN_STARTED` / `RUN_FINISHED` / `RUN_ERROR` | session open / close / error envelope |
| `TEXT_MESSAGE_START/CONTENT/END` | text token deltas |
| `TOOL_CALL_START/ARGS/END/RESULT` (streamed partial-JSON args) | tool-call events |
| `STATE_SNAPSHOT` / `STATE_DELTA` (RFC-6902 JSON Patch) | shared agent↔UI state (new) |
| `REASONING_*` / `THINKING_*` | reasoning deltas (already separated from text) |

The whole job is: emit our stream wrapped in the `RUN_STARTED … RUN_FINISHED` envelope over a Micronaut SSE endpoint. Estimated **~1 day**, since we already own the hard part (typed streaming). Frontend/client tools come back as a `ToolMessage` appended to `messages` on the next `POST` (each turn re-posts the full updated history + state).

**Build approach — hand-roll, no SDK dependency.** There is **no first-party JVM SDK**; the community Kotlin and Java SDKs in the repo are **client-side only** (they *consume* a remote agent's stream, they don't *serve* one), so neither helps us. Port the event enum as Kotlin sealed/data classes from the language-neutral protobuf source of truth (`sdks/typescript/packages/proto/src/proto/{events,types,patch}.proto`; the TS Zod `events.ts` is canonical and **docs lag the schema** — build against the schema, ~27–34 event types across lifecycle/text/tool/state/reasoning families). Do **not** adopt Atmosphere or AgentScope-Java — they import a rival agent model that fights our runtime.

**Why deferred.** Nice-to-have, not must-have, and lower priority than AGNTCY (which reaches agents/directories — our likelier near-term consumer). Two caveats kept on record: (1) **governance** — unlike A2A (Linux Foundation) and MCP (Agentic AI Foundation), AG-UI is still single-vendor (CopilotKit/Tawkit), MIT-licensed (no patent grant), not donated to any foundation as of June 2026; mitigated by the spec being small enough that lock-in barely bites. (2) **A2A/AG-UI streaming overlap** is asserted-but-undefended by sources (both use SSE); our read is A2A streams coarse task updates to a *calling agent* while AG-UI streams fine-grained render events to a *browser* — different consumer and granularity, so they compose. Re-evaluate to must-have if AG-UI is donated to a foundation.

Tracking: epic `[interop] AG-UI support (agent↔frontend serving)`, deferred until a concrete frontend need.

### 12.8 x402 — Agent Payments / Settlement Layer *(planned, deferred — money-handling)*

[x402](https://github.com/x402-foundation/x402) revives HTTP `402 Payment Required` to let agents pay for gated resources in **stablecoins (USDC), gaslessly**. Unlike §12.5–12.7 (which carry no money), x402 is a **settlement layer** — and it sits *beneath* the protocols we already target, not beside them. As of April 2026 it is **Linux-Foundation-governed** (x402 Foundation, 22 orgs incl. Coinbase, Cloudflare, AWS, Google, Circle, Visa, Mastercard, Amex, Stripe, Shopify); Apache-2.0.

**The flow.** Client requests a resource → server returns `402` + `PAYMENT-REQUIRED` header (`PaymentRequirements`: `scheme`, `network`, `maxAmountRequired`, `payTo`, `asset`, `resource`, `maxTimeoutSeconds`, `outputSchema`, `extra`) → client signs an **EIP-3009 `transferWithAuthorization`** (EIP-712; gasless — the facilitator submits on-chain) and resends with `PAYMENT-SIGNATURE` → server calls facilitator `/verify` then `/settle` → returns `200` + `PAYMENT-RESPONSE` receipt. Schemes: `exact`, **`upto`** (authorize a cap, settle actual usage — the natural metered-API model), `batch-settlement`. Networks: EVM/Base, Solana, Stellar. The **facilitator** (Coinbase CDP runs ~80%) is the trust chokepoint; sellers never touch a chain directly.

**Where it fits.** x402 is the settlement *rail* under our existing seams: an official **`a2a-x402` extension** (Google + Coinbase) and an **MCP `paidTool()`** wrapper already exist; Google's **AP2** is the *authorization* layer and x402 is its blessed *crypto rail*. AGNTCY and AG-UI have no payment dimension. So our insertion point is **an A2A x402 extension + an MCP paid-tool wrapper**, not a standalone payments module.

**Seller-side first (safe); buyer-side deferred behind hard custody guardrails.** These are different risk animals:
- **Seller-side** — our agents expose *paid* endpoints. We *receive* USDC via a hosted facilitator: **no custody, no money-transmitter exposure, no LLM-holds-key problem.** "Emit `402` → verify facilitator settlement → deliver" — a thin Micronaut middleware; may lean on the official Java SDK (`org.x402:x402`, SNAPSHOT — a servlet `PaymentFilter` + client; servlet/reactive impedance to weigh). This makes agents that can **monetize themselves** — a real differentiator. Ship **experimental**.
- **Buyer-side** — our agents autonomously *pay*. **Deferred.** All real-money risk lives here. Only behind scoped ERC-4337 session keys (on-chain per-tx caps, payee allowlists, velocity limits), **signing isolated from the model layer**, and human-in-the-loop for settlement. Separate, later, opt-in module.

**Non-negotiable design constraint:** **keep signing and spending limits below the model layer** — the LLM must never hold a key or carry a spend limit in its prompt. x402 moves **irreversible** money (no chargebacks; liability lands on the deployer), and prompt-injection drains are confirmed-real (Grok/Bankr ~$150–200k, Freysa $47k; peer-reviewed *Five Attacks on x402*). A self-hosted *custodial* facilitator likely triggers money-transmitter / stablecoin regulation (FinCEN MSB, GENIUS Act, MiCA) — prefer a hosted facilitator and never take custody on the seller path.

**Why deferred.** Lower strategic priority than the interop trio (A2A/AGNTCY/AG-UI are table-stakes with no money/custody/regulatory surface). Adoption is also early — real settled volume is ~\$28k/day (much of it wash-traded) over 13 months — so this is a forward-looking, on-trend bet, sequenced **after** the interop work, seller-side first.

Tracking: epic `[interop] x402 agent payments`, deferred — seller-side experimental, buyer-side gated.

### 12.9 NLWeb — Agent↔Web-Content / External Knowledge *(client ≈ free via MCP; server deferred)*

[NLWeb](https://github.com/nlweb-ai/NLWeb) (R.V. Guha — creator of RSS/RDF/schema.org — at Microsoft, announced Build 2025) is the **agent↔web-content** layer: it makes a website natural-language-queryable and returns **schema.org-typed JSON** results. Microsoft's framing — *"NLWeb is to MCP/A2A what HTML is to HTTP"* — is aspirational; mechanically it's **packaged RAG over schema.org/RSS data wrapped in an MCP interface**.

**The load-bearing fact: every NLWeb endpoint is also an MCP server.** Its `ask` interface rides on MCP transport, so **agents.kt's existing MCP client already consumes NLWeb sites** — no NLWeb-specific protocol code needed. NLWeb is therefore not a new protocol to implement; it's a *recognized use of MCP we already speak*. It's the **inbound external-knowledge counterpart to MCP-tools**; the only real overlap with anything we track is plain MCP servers, which is exactly why it's nearly free.

**Query shape** (the `/ask` and `/mcp` endpoints, same args): `query` (required), `site`, `prev` (conversation history — server is stateless), `mode` (`list` = ranked results, `summarize` = list + LLM summary, `generate` = full RAG answer), `streaming`. Response: `{query_id, results[]}` where each result is `{url, name, site, score, description, schema_object}` (`schema_object` = the schema.org JSON). Build tolerant of two divergent schemas — the implemented `schema_object` shape and the newer nlweb.ai v0.55 `query/context/prefer/meta` envelope.

**Client-side (consume NLWeb as knowledge) — do opportunistically, ~free.** A thin helper over the MCP client: point it at an NLWeb `/mcp` URL, `tools/call` the `ask` tool, surface each `schema_object` into a `KnowledgeProvider`/retrieval source. Mode mapping: `list`→retrieval source, `generate`→delegate-the-answer. The honest, shippable claim is *"agents.kt MCP clients can consume NLWeb endpoints today."*

**Server-side (expose agent data as an NLWeb endpoint) — deferred, niche.** That means standing up schema.org-shaped data + a vector store + an LLM-in-the-loop retrieval pipeline behind `/ask` + `/mcp` — effectively building/operating a RAG service. An independent benchmark (Univ. Mannheim, [arXiv 2511.23281](https://arxiv.org/abs/2511.23281)) finds NLWeb *ties* RAG/MCP on effectiveness but plain RAG is more cost-effective — so NLWeb's value is standardization, not performance. This is an **application** concern, not a runtime primitive; defer unless a concrete consumer needs to discover our content over the open web.

**Caveats.** NLWeb is **Microsoft-led, MIT, not foundation-governed** (the `nlweb-ai` org is a July-2025 rename of `microsoft/NLWeb`, not a donation — unlike A2A→Linux Foundation). Reference server is proof-of-concept quality (no CI/CD, no releases); an official .NET 9 impl exists but **no JVM/Kotlin port**. Adoption is partner-pilot grade.

**Priority — lowest net-new work of the external standards**, precisely because MCP subsumes the client capability. Order: MCP > A2A > AGNTCY ≈ AG-UI > x402 > NLWeb. It earns a thin client helper, not a dedicated workstream.

Tracking: epic `[interop] NLWeb support`, client helper opportunistic + server-side deferred.

---

## 13. Distributed Agents Framework

Agents.KT treats distribution as a property of *placement*, not of code. The same `Agent<IN, OUT>` definition can run in-process today and on a remote node tomorrow without changing call sites. A2A (§4) is the wire; the DSL is the API.

JAR bundling, folder-based assembly, hot deploy, and ClassLoader isolation are the packaging substrate (§12) that makes this possible. The runtime is planned for Phase 3+.

### 13.1 Locality Transparency

Composition operators don't care where participants execute:

```kotlin
val local  = agent<Spec, Code>("coder") { ... }
val remote = Agent.fromA2A<Code, Review>(
    "https://review.example.com/.well-known/agent.json"
)

val pipeline = local then remote   // ✅ types align, transport is invisible
```

`fromA2A<IN, OUT>` reifies the remote's AgentCard into a typed proxy. The compiler validates type alignment on the caller side; the runtime cross-checks the resolved AgentCard schema on first call and fails fast on drift.

### 13.2 Topologies Across Nodes

| Operator | Local nodes | Remote nodes | Notes |
|----------|------------|--------------|-------|
| `then` (Pipeline) | ✓ | ✓ | each stage may live anywhere |
| `*` (Forum) | ✓ | ✓ *(planned)* | participants discoverable via registry |
| `/` (Parallel) | ✓ | ✓ *(planned)* | fan-out across nodes; framework gathers |
| `.loop {}` | ✓ | ✓ | predicate runs at the orchestrator |
| `.branch {}` | ✓ | ✓ | sealed-type routing dispatches per-branch placement |
| `.spawn {}` *(planned)* | ✓ | ✓ | child may be placed on a different node |

The orchestrator is whatever node owns the outer structure. Inner agents may be local function calls, in-process coroutines, or A2A round-trips — uniform.

### 13.3 Discovery

Three modes, in increasing dynamism:

1. **Static URL** — `Agent.fromA2A<>("https://.../agent.json")`. No registry.
2. **Catalog** — a project-scoped JSON catalog mapping logical name → URL, resolved at assembly time. Supports environment overrides (dev/staging/prod).
3. **Registry** *(planned)* — A2A-compatible endpoint returning AgentCards by capability query (e.g., "produces ReviewResult, tag=kotlin"). The runtime caches results with TTL.

### 13.4 Failure Model

A2A calls are I/O — they fail. Remote-call errors are infrastructure errors (§17), routed through `onError`:

| Failure | Detection | Default | Override |
|---------|-----------|---------|----------|
| Connect timeout | per-call deadline | retry with backoff (3×) | `onError { remoteUnreachable -> ... }` |
| Response timeout | per-call deadline | fail fast | `timeout(...)` per skill |
| Schema drift | AgentCard hash mismatch | fail at startup | warn + adapter *(planned, §23 OQ #7)* |
| 5xx from remote | HTTP status | retry with backoff (3×) | classify per status |
| 4xx from remote | HTTP status | fail fast | `onError` for tool-shaped errors |
| Partial failure (parallel) | per-branch | gather successes, surface failures | `parallelPolicy { allOrNothing \| bestEffort }` |

Circuit breaking and bulkheads land in Phase 4 alongside Forum/Parallel across nodes.

### 13.5 Type Safety Across Node Boundaries

Three validation layers:

- **Compile-time (caller):** `Agent.fromA2A<IN, OUT>` requires explicit types. Pipelines composing remote agents are type-checked exactly like local ones.
- **Assembly-time:** if the AgentCard is reachable at assembly, the framework cross-checks declared `IN`/`OUT` against the card's `defaultInputModes` / `defaultOutputModes` and `@Generable` schema fingerprints.
- **First-call:** the runtime fetches the live AgentCard, validates the schema hash, and caches. Drift triggers `onError(SchemaDrift)`.

### 13.6 Placement Manifest *(planned)*

A declarative file pinning which agents in a Team Bundle (§12.4) run where:

```yaml
# placement.yaml
agents:
  spec-master: { node: orchestrator }
  coder:       { node: gpu-pool, replicas: 4 }
  reviewer:    { remote: "https://review.deepcode.ai/.well-known/agent.json" }
```

At boot the runtime wires local agents in-process and materializes A2A proxies for remote ones. The composition graph in Kotlin source is unchanged — placement is config.

### 13.7 What This Framework Is Not

- **Not a service mesh.** Agents.KT relies on A2A; it ships no discovery, mTLS, or load balancer of its own. Plug into existing infra (Consul, Istio, K8s services) at the URL layer.
- **Not actor-style messaging.** Communication is request/response over A2A. Long-lived state belongs in `memory {}` (§8.5), not in node identity.
- **Not RPC code-gen.** No `.proto` files. Types come from `@Generable` Kotlin classes; AgentCards are derived.

### 13.8 Status

| Capability | Phase |
|------------|-------|
| `Agent.fromA2A<>()` typed proxy | 3 |
| Pipelines with remote stages | 3 |
| Catalog-based discovery | 3 |
| Placement manifest | 3 |
| Schema drift detection | 3 |
| Registry-based discovery | 4 |
| Forum / Parallel across nodes | 4 |
| Circuit breaker / bulkhead | 4 |

---

## 14. Gradle Plugin + CLI

### 14.1 Shared Core

```
┌───────────────────────────────────────────┐
│              agents-core                   │
│  Parser · Validator · Assembler            │
│  Serializer · A2A Generator                │
│  TypeResolver · PermissionChecker          │
│  → Maven: dev.agentskt:agents-core    │
└──────────┬──────────────────┬─────────────┘
           │                  │
    ┌──────▼──────┐   ┌──────▼──────────┐
    │ agents CLI  │   │ Gradle Plugin   │
    │ (for humans │   │ (for projects   │
    │  and ops)   │   │  and CI/CD)     │
    └─────────────┘   └─────────────────┘
```

### 14.2 Gradle Plugin

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.1.0"
    id("dev.agentskt") version "0.5.0"
}

dependencies {
    agent("com.deepcode:spec-master:1.0.0")
    agent("com.deepcode:coder:2.1.0")
    knowledgepack("dev.agentskt.packs:kotlin-bp:1.0.0")
    agentTypes("com.deepcode:deep-code-types:1.0.0")
}

agentsKt {
    agents {
        sourceDir = "agents/definitions"
        knowledgeDir = "agents/knowledge"
    }

    bundles {
        agent("reviewer") { }
        team("deep-code-team") {
            structure = "agents/structures/deep-code.kt"
            includeAll()
        }
        knowledgePack("company-standards") {
            source = "agents/knowledge/packs/company-standards/"
        }
    }

    a2a {
        generateCards = true
        provider {
            organization = "K.Skobeltsyn Studio"
            url = "https://kskobeltsyn.ru"
        }
    }

    validation { strict = true; knowledgeFileChecks = true }

    testing {
        models {
            mock = MockModel.pattern { ... }
            judge = Models.claude()
        }
        coverage {
            minSkillCoverage = 100
            minToolCoverage = 80
        }
        ciLevels {
            pr = setOf(UNIT, BEHAVIORAL, STRUCTURAL)
            merge = setOf(UNIT, BEHAVIORAL, SEMANTIC)
            nightly = ALL
            release = ALL + REGRESSION
        }
    }
}
```

**Gradle tasks:**

| Task | Description |
|------|-------------|
| `agentsValidate` | Run all 26 compile-time checks |
| `agentsBuild` | Compile + validate + bundle |
| `agentsBundle -Pagent=X` | Bundle single agent JAR |
| `agentsBundleTeam` | Bundle team JAR (all agents + structure) |
| `agentsA2ACards` | Generate A2A AgentCard JSONs |
| `agentsA2AServe` | Start A2A dev server with hot-reload |
| `agentsGraph` | Print delegation/pipeline graph |
| `agentsGraph --format=mermaid` | Export as Mermaid diagram |
| `agentsPublish` | Publish bundles to Maven repository |
| `agentsTest` | Run all AgentUnit tests |
| `agentsTest --level=pr` | Run PR-level tests only |
| `agentsTestCoverage` | Generate Skill Coverage report |

### 14.3 CLI Tool

```bash
# Installation
brew install agentskt
# or: curl -sL https://get.agentskt.dev | bash

# ═══ SCAFFOLDING ═══
agents new my-team                            # Full project with Gradle
agents new my-team --minimal                  # Just folder + team.yaml
agents generate agent coder --consumes Spec --produces Code --skills write-code
agents generate structure review-team --agents coder,reviewer
agents generate skill refactor --agent coder --tools analyze,rewrite

# ═══ JAR OPERATIONS ═══
agents inspect coder-2.1.0.jar               # Show metadata, types, skills
agents diff coder-2.1.0.jar coder-3.0.0.jar  # Compare versions
agents check deploy/agents/                    # Validate compatibility

# ═══ ASSEMBLY + RUNTIME ═══
agents serve deploy/                           # Scan → resolve → serve
agents serve deploy/ --watch                   # Hot deploy on JAR changes
agents serve deploy/ --dry-run                 # Show what would happen
agents assemble deploy/ --generate-structure   # Auto-generate structure.kt

# ═══ A2A ═══
agents a2a-card coder-2.1.0.jar --url https://api.example.com/coder

# ═══ TESTING ═══
agents test                                    # Run all tests
agents test --agent coder                      # Test specific agent
agents test --tag semantic                     # Test by category
agents test --coverage                         # With Skill Coverage

# ═══ REPL ═══
agents console deploy/
> agents                                       # List loaded agents
> pipeline                                     # Show type chain
> skills                                       # List all skills
> test coder.write-code "Implement user API"   # Test single skill
> send "Build REST API for users"              # Run full pipeline
> a2a-card                                     # Show team AgentCard
```

---

## 15. Distribution: Zero-Dependency Installation

### 15.1 The JRE Problem

Agents.KT is built in Kotlin/JVM, but requiring Java installation kills adoption. Nobody installs a 300MB JDK for a CLI tool. Solution: **two artifacts, two strategies**.

```
agents CLI     → GraalVM Native Image → single binary, zero deps
agents runtime → JVM + jlink          → minimal bundled JRE (~35MB)
```

The CLI (scaffold, validate, inspect, generate, visualize) compiles to a **native binary** via GraalVM — no JRE needed, works like any Go or Rust binary. The runtime (serve, execute agents, load JARs) bundles a **minimal JRE** via jlink — auto-downloaded on first use.

### 15.2 Two Artifacts

| Artifact | Technology | Size | JRE Required | Purpose |
|----------|-----------|------|-------------|---------|
| `agents` CLI | GraalVM Native Image | ~40MB | **No** | Scaffold, validate, inspect, generate, visualize, A2A cards |
| `agents-runtime` | Kotlin JAR + jlink JRE | ~50MB | **Bundled** | Serve, execute, load agent JARs, run tests with real models |

The CLI auto-downloads the runtime on first use of `serve`, `console`, or `test --tag semantic`:

```
$ agents serve deploy/

Runtime not found. Downloading agents-runtime-0.5.0...
  Platform: linux-x64
  Size: 48MB (includes minimal JRE)
  Location: ~/.agentskt/runtime/0.5.0/
Downloading... ████████████████████ 100%
Installed. ✓

Starting server...
```

### 15.3 Installation Channels

**Homebrew (macOS / Linux):**

```bash
brew tap agentskt/tap
brew install agentskt
# Installs native binary. No Java.
```

**npm (cross-platform, JS/TS ecosystem):**

```bash
npm install -g @agentskt/cli
# or without installing:
npx @agentskt/cli new my-team
```

Platform-specific native binary downloaded via `optionalDependencies` — same pattern as esbuild, turbo, prisma.

**pip (Python ecosystem, LangChain migrants):**

```bash
pip install agentskt
agents new my-team
```

Python wrapper downloads native binary on install — same pattern as ruff, black.

**curl | bash (universal):**

```bash
curl -sL https://get.agentskt.dev | bash
# Detects OS + arch, downloads binary, adds to PATH
```

**SDKMAN! (JVM ecosystem):**

```bash
sdk install agentskt
```

**apt / yum (Linux servers):**

```bash
# Debian/Ubuntu
curl -sL https://packages.agentskt.dev/gpg | sudo apt-key add -
echo "deb https://packages.agentskt.dev/apt stable main" | \
  sudo tee /etc/apt/sources.list.d/agentskt.list
sudo apt update && sudo apt install agentskt
```

**Docker (production runtime):**

```bash
docker run -v ./deploy:/app ghcr.io/agentskt/runtime:0.5
# Contains: jlink JRE + runtime. Just works.
```

**Gradle plugin (dev projects):**

```kotlin
plugins { id("dev.agentskt") version "0.5.0" }
// No separate install. Gradle downloads everything.
```

### 15.4 Channel Matrix

| Channel | CLI (native) | Runtime (JRE bundled) | Primary Audience |
|---------|:------------:|:---------------------:|-----------------|
| Homebrew | ✅ | ✅ auto-download | macOS / Linux devs |
| npm | ✅ | ❌ use Docker | JS/TS devs, quick start |
| pip | ✅ | ❌ use Docker | Python devs, LangChain migrants |
| curl \| bash | ✅ | ✅ auto-download | Universal, CI |
| SDKMAN | ✅ | ✅ auto-download | Kotlin/JVM devs |
| apt / yum | ✅ | ✅ as package | Linux servers, DevOps |
| Docker | — | ✅ built-in | Production, Kubernetes |
| Gradle plugin | — | ✅ via Gradle | Dev projects, CI/CD |
| GitHub Releases | ✅ all platforms | ✅ all platforms | Manual / air-gapped |

### 15.5 Runtime Bundle Structure

The runtime is a self-contained package with a minimal JRE produced by jlink:

```
~/.agentskt/
├── bin/
│   └── agents                  # native CLI binary
├── runtime/
│   ├── 0.5.0/
│   │   ├── bin/
│   │   │   └── agents-serve    # launcher: ./jre/bin/java -jar runtime.jar
│   │   ├── jre/                # minimal JRE from jlink (~35MB)
│   │   │   ├── bin/java
│   │   │   └── lib/
│   │   └── lib/
│   │       └── agents-runtime.jar
│   └── 0.4.0/                  # can keep multiple versions
└── cache/
    └── knowledge-packs/         # cached downloaded packs
```

jlink includes only required JVM modules: `java.base`, `java.net.http`, `java.sql`, `jdk.crypto.ec` — ~35MB instead of ~300MB full JDK.

### 15.6 User Journeys

**Python dev migrating from LangChain:**

```bash
pip install agentskt                  # native binary, instant
agents new my-team                          # scaffold
# ... writes agents in Kotlin DSL ...
agents validate                             # native, instant
agents serve deploy/                        # first time: downloads runtime
                                            # subsequent: instant start
```

**Frontend dev exploring agents:**

```bash
npx @agentskt/cli new my-team         # no install needed
npx @agentskt/cli validate            # runs immediately
# For serve: docker run -v ./deploy:/app ghcr.io/agentskt/runtime:0.5
```

**Kotlin dev (primary audience):**

```kotlin
// build.gradle.kts — Gradle handles everything
plugins { id("dev.agentskt") version "0.5.0" }
```

```bash
./gradlew agentsValidate                    # no separate install
./gradlew agentsServe                       # Gradle manages JRE
```

**DevOps deploying to production:**

```bash
sudo apt install agentskt              # CLI + runtime
agents serve /opt/agents/ --watch --port 8080 --daemon
# or
docker run -d -v /opt/agents:/app -p 8080:8080 ghcr.io/agentskt/runtime:0.5
```

### 15.7 Build Pipeline

```
Kotlin Source
    │
    ├─→ GraalVM native-image ─→ agents CLI binary (per platform)
    │     ├→ GitHub Releases (linux-x64, linux-aarch64, macos-x64, macos-aarch64, windows-x64)
    │     ├→ Homebrew formula
    │     ├→ npm optional platform packages
    │     ├→ pip wheel with embedded binary
    │     ├→ SDKMAN candidate
    │     └→ apt/yum packages
    │
    ├─→ Gradle build ─→ agents-runtime.jar (fat JAR)
    │     ├→ Maven Central (library use)
    │     └→ Gradle plugin repository
    │
    ├─→ jlink ─→ minimal JRE (~35MB)
    │     └─→ Combined with runtime.jar → self-contained bundle
    │           ├→ GitHub Releases (per platform)
    │           ├→ Auto-download by CLI on first `serve`
    │           └→ apt/yum packages
    │
    └─→ Docker ─→ ghcr.io/agentskt/runtime:0.5
          └→ Contains: jlink JRE + runtime.jar + agents CLI
```

---

## 16. Testing

Testing uses **JUnit**. Agents are Kotlin functions — standard testing applies. Agent-specific testing framework (semantic assertions, LLM-as-judge, skill coverage) planned for future phases.

```kotlin
@Test fun `coder produces compilable output`() = runTest {
    val coder = agent<Specification, CodeBundle>("coder") { /* ... */ }
    val result = coder(sampleSpec)
    assertTrue(result.compiles())
}

@Test fun `pipeline chains correctly`() = runTest {
    val pipeline = specMaster then coder then reviewer
    val result = pipeline(TaskRequest("Build user API"))
    assertIs<ReviewResult.Passed>(result)
}

@Test fun `skill knowledge loads correctly`() {
    val ctx = writeFromScratch.toLlmContext()
    assertTrue(ctx.contains("Prefer val over var"))
}
```

---

## 17. Tool Error Recovery

### 17.1 The Problem

LLMs produce malformed tool calls. JSON with trailing commas, missing required fields, wrong types, OS-specific path separators, markdown fencing around JSON, hallucinated field names. Every agent framework hits this wall. The standard industry response is a dedicated `StructureFixingParser` class (Koog), a `repairToolCall` callback (Vercel AI SDK), or runtime retry-and-pray.

Agents.KT takes a different position: **the fixer is an agent**. No special parser class, no dedicated repair interface. An `Agent<String, String>` that takes broken input and returns fixed output — with full access to the same skills, tools, telemetry, budget tracking, and governance as every other agent in the system.

### 17.2 Two Failure Classes

Tool errors split into two fundamentally different categories:

**Tool-local failures** — the tool itself received garbage. Malformed JSON arguments, deserialization errors, encoding issues, OS-specific path problems. The tool knows its own schema and can fix itself. The parent agent has no business knowing about backslash normalization.

**Domain failures** — the tool executed successfully but the result is wrong in context. A file written outside the project boundary, generated code that doesn't match the spec, a query that violates business rules. The tool has no idea it did anything wrong — only the agent, with its domain knowledge, can judge.

This section covers tool-local failures. Domain failures are the agent's responsibility via `constraints {}` (Section Y).

### 17.3 Error Taxonomy

Tool-local errors form a sealed hierarchy:

```kotlin
sealed interface ToolError {
    /** LLM produced syntactically invalid arguments (malformed JSON, wrong types) */
    data class InvalidArgs(
        val rawArgs: String,
        val parseError: String,
        val expectedSchema: JsonSchema
    ) : ToolError

    /** Arguments parsed but a specific value failed deserialization
     *  (encoding, path separators, type coercion) */
    data class DeserializationError(
        val rawValue: String,
        val targetType: KType,
        val cause: Throwable
    ) : ToolError

    /** Tool executed but threw at runtime (timeout, network, IO, OOM) */
    data class ExecutionError(
        val args: ToolArgs,
        val cause: Throwable
    ) : ToolError

    /** A repair agent called escalate() — it cannot fix the problem */
    data class EscalationError(
        val source: AgentRef,
        val reason: String,
        val severity: Severity,
        val originalError: ToolError,
        val attempts: Int
    ) : ToolError
}
```

Every error carries enough context for either deterministic or LLM-driven repair — no re-parsing needed downstream.

### 17.4 The `onError` DSL

Each tool declares its own error recovery strategy inside its block via `onError {}`. Three verbs correspond to three error types:

```kotlin
agent<Spec, Code>("coder") {
    tools {
        tool("write_file") {
            description("Write a file to disk")
            executor { args -> writeFile(args["path"].toString(), args["code"].toString()) }
            onError {
                invalidArgs   { args, error -> fix(agent = jsonFixer) }
                deserializationError { raw, error -> sanitize(agent = pathSanitizer) }
                executionError { e -> retry(maxAttempts = 3) }
            }
        }
    }
}
```

The tool block DSL provides `description(...)`, `executor { }`, and `onError { }`. Error handling is declared where the tool lives — not as a separate callback.

Three forms are supported, in priority order:

**1. Inside the tool block** (highest priority):

```kotlin
tool("parse") {
    description("Parse JSON")
    executor { args -> parseJson(args) }
    onError {
        executionError { _ -> fix(agent = jsonFixer, retries = 3) }
    }
}
```

**2. Agent-level `onToolError`** (middle priority):

```kotlin
onToolError("parse") {
    executionError { _ -> fix(agent = jsonFixer, retries = 3) }
}
```

**3. Tool-level defaults** (lowest priority):

```kotlin
tools {
    defaults {
        onError {
            invalidArgs { _, _ -> fix(agent = jsonFixer, retries = 3) }
            executionError { _ -> retry(maxAttempts = 2) }
        }
    }

    tool("write_file") {                                  // inherits defaults
        description("Write file")
        executor { args -> writeFile(args) }
    }
    tool("compile") {                                     // overrides defaults
        description("Compile code")
        executor { args -> compile(args) }
        onError { executionError { _ -> retry(maxAttempts = 1) } }
    }
}
```

Resolution: tool block `onError` > agent-level `onToolError` > defaults. Unhandled error types propagate as unrecoverable failures.

### 17.5 The Fixer Is an Agent

Every repair verb takes an `Agent<String, String>`. No lambdas, no special parser classes — the same abstraction used everywhere else. **The fixing agent is a regular `Agent<String, String>`** — same type system, same composition, same telemetry, same everything.

#### Deterministic repair (zero LLM calls)

A repair agent does not require an LLM. `implementedBy` with a pure function is already part of the skill model (Section 5.2.2):

```kotlin
val jsonFixer = agent<String, String>("json-fixer") {
    skills {
        skill<String, String>("cleanup", "Fixes common JSON issues") {
            implementedBy { input ->
                input
                    .trimMarkdownFencing()
                    .fixTrailingCommas()
                    .normalizeQuotes()
                    .unescapeUnicode()
                    ?: escalate("Not JSON at all — binary or completely garbled")
            }
        }
    }
}
```

Same `Agent<String, String>`. Same slot in `onError`. Same telemetry, same budget tracking, same `agentTest {}`. The caller does not know or care whether the fixer uses an LLM or a regex. This is the fractal composition principle (Design Principle 4) applied to error recovery.

#### LLM-driven repair

When deterministic fixes aren't enough, the repair agent can use a model:

```kotlin
val jsonFixer = agent<String, String>("json-fixer") {
    prompt("Fix malformed JSON. Preserve all data. Make minimal changes only.")
    model { cheap("gpt-4o-mini"); temperature = 0.0 }
}

val sanitizer = agent<String, String>("sanitizer") {
    prompt("Clean the raw value to match the target type. Return only the corrected value.")
    model { cheap("gpt-4o-mini"); temperature = 0.0 }
}

onError {
    invalidArgs { _, _ -> fix(agent = jsonFixer, retries = 3) }
    deserializationError { _, _ -> sanitize(agent = sanitizer, retries = 2) }
    executionError { _ -> retry(maxAttempts = 3) }
}
```

The framework packs context into the agent's input string automatically:

| Verb | Input to agent | Context injected |
|------|---------------|-----------------|
| `fix` | Broken JSON + parse error | `tool.paramsSchema` |
| `sanitize` | Raw value + deserialization error | `param.type` (single field) |

### 17.6 Built-in Tools: `escalate` and `throwException`

Every agent has two framework-provided tools — `escalate` and `throwException` — registered in `toolMap` at construction time. They are **inactive by default**: present in every agent but only available to skills that explicitly reference them via `tools("escalate", "throwException")`.

```kotlin
val fixer = agent<String, String>("json-fixer") {
    prompt("Fix malformed JSON. If structural error, call escalate. If binary garbage, call throwException.")
    model { cheap("gpt-4o-mini"); temperature = 0.0 }
    skills {
        skill<String, String>("fix", "Fix JSON") {
            tools("escalate", "throwException")   // activates built-in tools for this skill
        }
    }
}
```

**`escalate`** — soft failure. Args: `reason` (string), `severity` (LOW/MEDIUM/HIGH/CRITICAL, optional, defaults to HIGH).

The repair loop stops. The error is **fed back to the parent LLM as a tool result**, giving it a chance to retry with corrected arguments. The parent agent's LLM sees the error message and can adjust its next tool call.

**`throwException`** — hard failure. Args: `reason` (string).

A `ToolExecutionException` propagates through the pipeline. No retries, no second chances. Use when the input is fundamentally unrecoverable (binary data, wrong protocol, etc.).

Deterministic agents can also signal escalation and hard failure by throwing directly:

```kotlin
implementedBy { _ ->
    throw EscalationException("Schema mismatch", Severity.HIGH)  // soft — fed back to LLM
    // or
    throw ToolExecutionException("Binary data, not JSON")         // hard — kills the run
}
```

Both emit `PipelineEvent` entries — telemetry sees every escalation and every exception.

### 17.7 Escalation Flow

Escalation feeds the error back to the parent LLM, which can retry with corrected data:

```
LLM calls parseJson(json = "{name: world}")
  → tool throws: "unquoted keys"
  → tool.onError.executionError triggered
    → json-fixer agent invoked
      → fixer LLM calls escalate("Unquoted keys. Corrected: {\"name\":\"world\"}")
        → error fed back to parent LLM as tool result:
            "ERROR: Tool 'parseJson' failed: Unquoted keys. Corrected: {\"name\":\"world\"}"
          → parent LLM retries: parseJson(json = '{"name":"world"}')
            → tool succeeds → LLM continues
```

If the parent LLM keeps failing, the budget limit (`maxTurns`) stops the loop. `throwException` bypasses this — it propagates immediately as an exception.

If the parent agent also escalates, the error walks up the delegation tree until a handler is found or the root agent receives it. This is `throw` for agents — typed, observable, and following the org chart.

### 17.8 Full Example: JSON Key Counter with Escalation Recovery

A complete working example demonstrating the full error recovery cycle — tool failure, LLM-driven fixer agent, escalation via built-in tool, error fed back to parent LLM, retry with corrected data:

```kotlin
// ─── Fixer Agent ───
// LLM-driven repair agent. Analyzes the parse error and calls the built-in
// escalate tool with the corrected JSON in the reason string.
val jsonFixer = agent<String, String>("json-fixer") {
    prompt(
        "You receive a string that failed to parse as JSON. " +
        "Analyze the error. Call the escalate tool with a reason " +
        "that includes the corrected valid JSON."
    )
    model { ollama("gpt-4o-mini"); temperature = 0.0 }
    budget { maxTurns = 3 }
    skills {
        skill<String, String>("fix", "Analyze and escalate JSON errors") {
            tools("escalate")   // activates the built-in escalate tool
        }
    }
}

// ─── Main Agent ───
// Uses calculateNumberOfKeys tool with onError inside the tool block.
// When the tool fails, the fixer agent is invoked. When the fixer escalates,
// the error is fed back to this agent's LLM, which retries with corrected data.
val analyst = agent<String, String>("json-analyst") {
    prompt(
        "Use the calculateNumberOfKeys tool to count keys in JSON objects. " +
        "The tool takes one argument: json (a valid JSON string with double-quoted keys). " +
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
                if (keys.isEmpty()) {
                    throw IllegalArgumentException(
                        "No valid keys found — JSON may have unquoted keys"
                    )
                }
                keys.size
            }
            onError {
                executionError { _ -> fix(agent = jsonFixer, retries = 2) }
            }
        }
    }
    skills {
        skill<String, String>("solve", "Analyze JSON using tools") {
            tools("calculateNumberOfKeys")
        }
    }
}

analyst("How many keys? {name: world, age: 30, active: true}")
// → "3"
```

**What happens at runtime:**

```
1. LLM calls calculateNumberOfKeys(json = "{name: world, age: 30, active: true}")
   → Tool throws: "No valid keys found — JSON may have unquoted keys"

2. tool.onError.executionError triggered
   → jsonFixer agent invoked with the error as input

3. jsonFixer LLM analyzes the error
   → Calls escalate(reason = "Unquoted keys. Corrected: {\"name\":\"world\",\"age\":30,\"active\":true}")
   → EscalationException caught by framework

4. Error fed back to parent LLM as tool result:
   "ERROR: Tool 'calculateNumberOfKeys' failed: Unquoted keys. Corrected: {...}"

5. Parent LLM reads the corrected JSON from the error message
   → Retries: calculateNumberOfKeys(json = '{"name":"world","age":30,"active":true}')
   → Tool succeeds → returns 3

6. LLM returns "3"
```

Key design points demonstrated:
- **The fixer is an agent** — same `Agent<String, String>`, same composition, same telemetry
- **`escalate` is a built-in tool** — always present, activated by `tools("escalate")`
- **Escalation is soft** — error fed back to the LLM, not thrown as an exception
- **`onError` inside the tool block** — error handling declared where the tool lives
- **Budget limits** prevent infinite retry loops

### 17.9 Compile-Time Validations (Planned)

| # | Check | Severity |
|---|-------|----------|
| 1 | `onError` handler references a tool that exists in the agent's `tools {}` block | Error |
| 2 | Repair agent type must be `Agent<String, String>` | Error |
| 3 | `escalate()` and `throwException()` are only available inside agents used as repair agents | Warning |
| 4 | `defaults` onError handlers don't conflict with per-tool overrides | Warning |
| 5 | Repair agent's model must be configured (no model = deterministic only) | Info |

### 17.10 Competitive Comparison

| Framework | Mechanism | Type-safe | Observable | Testable | Composable |
|-----------|-----------|-----------|------------|----------|------------|
| **Koog** | `StructureFixingParser` class | Partial (reified) | ❌ Black box | ❌ No test API | ❌ Standalone class |
| **Vercel AI SDK** | `repairToolCall` callback | ❌ Untyped JS | ❌ Manual logging | ❌ No framework | ❌ Callback only |
| **Pydantic AI** | `RetryPromptPart` + validators | ✅ Pydantic schema | Partial (Logfire) | Partial | ❌ Coupled to run loop |
| **DSPy** | `Assert`/`Suggest` + backtracking | ❌ Dynamic Python | ❌ Internal only | ❌ No isolation | ❌ Module transform |
| **Agents.KT** | `Agent<String, String>` in `onError {}` | ✅ Full type system | ✅ PipelineEvent | ✅ AgentUnit | ✅ Same composition as everything |

The key differentiator: every other framework invented a new abstraction for error repair. Agents.KT uses the abstraction it already has — the agent.

---

## 18. Project Structure

```
agents/
├── definitions/           # Layer 1: agent<IN,OUT> definitions
│   ├── spec-master.kt
│   ├── coder.kt
│   └── reviewer.kt
├── structures/            # Layer 2: structure assemblies
│   └── deep-code.kt
├── types/                 # Domain types (sealed interfaces)
│   ├── Specification.kt
│   ├── CodeBundle.kt
│   └── ReviewResult.kt
├── tools/                 # Tool implementations
├── knowledge/             # Knowledge content files (developer-organized)
├── models/                # LLM connection configs
├── tests/                 # JUnit tests
├── build.gradle.kts
└── main.kt
```

---

## 19. Competitive Landscape

- **LangChain (Python)** — largest ecosystem, 100x community, no typed contracts, no compile-time validation
- **CrewAI (Python)** — fast to prototype, role-based agents, no type safety, flat architecture
- **Koog (Kotlin, JetBrains)** — Kotlin-native, multiplatform, behavior graphs; tool deserialization breaks in practice; no typed pipeline composition
- **Mastra (TypeScript)** — visual builder, model router, good DX; no hierarchical delegation, no permission model
- **Pydantic AI (Python)** — typed Python with validation, growing fast; no composition operators, no knowledge model
- **AutoGen (Python, Microsoft)** — strong multi-agent conversation; complex API, no typed contracts
- **Semantic Kernel (C#/Python, Microsoft)** — enterprise, planner architecture; no agent composition, heavy abstraction

**Agents.KT positioning:** typed `Agent<IN, OUT>` contracts, `@Generable` guided generation, `Tool<IN, OUT>` with MCP inheritance, fractal composition operators (`then`/`*`/`/`/`.loop`/`.branch`), code-based skill knowledge with two delivery models. The gap we fill: no framework connects typed contracts → composition validation → MCP-native tools → knowledge delivery in one coherent system.

---

## 20. Full Example: Deep-Code.AI

```kotlin
// ─── Domain Types ───

sealed interface Specification {
    data class OpenAPI(val schema: JsonObject) : Specification
    data class UML(val diagram: String) : Specification
}

sealed interface CodeBundle {
    data class KotlinProject(val files: Map<String, String>) : CodeBundle
    data class SingleFile(val content: String) : CodeBundle
}

sealed interface ReviewResult {
    data class Passed(val score: Double) : ReviewResult
    data class Failed(val issues: List<String>) : ReviewResult
    data class NeedsRevision(val feedback: String) : ReviewResult
}


// ─── Knowledge Packs ───

val kotlinBP = knowledgePack("kotlin-bp") {
    knowledge("idioms", "Kotlin idiomatic patterns") { loadFile("code/kotlin-idioms.md") }
    knowledge("coroutines", "Coroutine patterns") { loadFile("code/coroutines-patterns.md") }
}


// ─── Layer 1: Agent Definitions ───

val specMaster = agent<TaskRequest, Specification>("spec-master") {
    description = "Creates and validates technical specifications"
    version = "1.0.0"

    skills {
        skill<TaskRequest, Specification>("create-openapi",
            "Creates a complete OpenAPI 3.1 specification from a plain-text task description") {
            knowledge("conventions") { loadFile("specs/openapi-conventions.md") }
            knowledge("checklist") { loadFile("specs/checklists/api-design.md") }
            implementedBy { tools("create_spec") }
        }
        skill<TaskRequest, Specification>("create-uml",
            "Produces a UML class or sequence diagram from a task description") {
            implementedBy { tools("draw_uml") }
        }
    }

    tools {
        tool("create_spec") { param("title", STRING); returns(SPEC_REF) }
        tool("draw_uml") { param("description", STRING); returns(UML_REF) }
    }

    capabilities { streaming = true }
    requires { tools(createSpec, searchSpec) }  // agent declares which tools it needs
    model { title = "qwen-2.5-coder"; temperature = 0.2 }
}

val coder = agent<Specification, CodeBundle>("coder") {
    description = "Writes production Kotlin code from specifications"
    version = "2.1.0"

    skills {
        skill<Specification, CodeBundle>("write-code",
            "Writes production Kotlin code from a specification and compiles it") {
            knowledge("style-guide") { loadFile("code/kotlin-idioms.md") }
            knowledge("checklist") { loadFile("code/checklists/pre-commit.md") }
            implementedBy { tools("write_file", "compile") }
        }
        skill<Specification, CodeBundle>("write-and-test",
            "Writes code and immediately adds unit tests — use when test coverage is required") {
            knowledge("tdd-guide") { loadFile("code/write-with-tests.md") }
            implementedBy {
                pipeline { self + tester }  // coder writes, tester tests
            }
        }
    }

    tools {
        tool("write_file") { param("path", STRING); param("content", STRING) }
        tool("compile") { param("target", ENUM("jvm", "native")); returns(COMPILE_RESULT) }
    }

    requires { tools(writeFile, compile) }
    model { title = "qwen-2.5-coder"; temperature = 0.1 }
}

val reviewer = agent<CodeBundle, ReviewResult>("reviewer") {
    description = "Reviews code for quality, security, and best practices"
    version = "1.3.0"

    skills {
        skill<CodeBundle, ReviewResult>("code-review",
            "Reviews Kotlin code for correctness, security vulnerabilities, and idiomatic style") {
            knowledge("security-checklist") { loadFile("code/checklists/review.md") }
            knowledge("kotlin-idioms") { loadFile("code/kotlin-idioms.md") }
            implementedBy { tools("lint", "review") }
        }
    }

    tools {
        tool("lint") { param("code", CODE_BUNDLE); returns(LINT_RESULT) }
        tool("review") { param("code", CODE_BUNDLE); returns(REVIEW_RESULT) }
    }

    requires { tools(lint, review) }
    model { title = "qwen-2.5-coder"; temperature = 0.3 }
}

val deployer = agent<CodeBundle, DeployResult>("deployer") {
    description = "Deploys code to Kubernetes"
    version = "1.0.0"

    skills {
        skill("deploy-k8s") {
            knowledge { skill("ops/deploy-k8s.md") }
            implementedBy { tools("docker_build", "kubectl_apply") }
        }
    }

    tools {
        tool("docker_build") { param("path", STRING); returns(IMAGE_REF) }
        tool("kubectl_apply") { param("image", IMAGE_REF); returns(DEPLOY_STATUS) }
    }

    requires { tools(dockerBuild, kubectlApply) }
}


// ─── Type-Safe Composition ───

// Simple pipeline
val review = specMaster then coder then reviewer
// Pipeline<TaskRequest, ReviewResult>

// With branching on review result
val fullPipeline = specMaster then coder then reviewer.branch {
    on<ReviewResult.Passed>()        then deployer
    on<ReviewResult.Failed>()        then coder then reviewer  // retry
    on<ReviewResult.NeedsRevision>() then coder then reviewer  // fix
}
// Pipeline<TaskRequest, DeployResult>


// ─── Layer 2: Structure ───

structure("deep-code") {
    root(project) {
        grants { tools(*) }
        budget { maxTokens = 500_000; maxTime = 30.minutes }

        delegates(specMaster) { grants { tools(createSpec, drawUml) } }

        delegates(coder) { grants { tools(writeFile, compile) } }

        delegates(reviewer) { grants { tools(lint, review) } }

        delegates(deployer) {
            grants { tools(dockerBuild) }
            confirm(kubectlApply)  // requires user approval
        }

        workflow("full") { fullPipeline }
    }
}


// ─── Tests ───

agentTest("coder") {
    withModel(MockModel.fromFixture("fixtures/coder.json"))

    test("write-code compiles") {
        val output = agent.skill("write-code").execute(sampleSpec)
        expect {
            output.compiles()
            agent.calledTool("compile").successfully()
            agent.passedChecklist("pre-commit.md")
        }
    }
}

pipelineTest("full") {
    test("end-to-end") {
        val output = pipeline(fullPipeline).execute(TaskRequest("Build user API"))
        expect {
            stage(reviewer) { output.jsonField("$.passed").equals(true) }
            pipeline.totalTokens() <= 50_000
        }
    }
}
```

---

## 21. UML Isomorphism (Deep-Code.AI Integration)

| DSL Concept | UML Equivalent |
|-------------|---------------|
| `agent<IN,OUT>` | Component with typed ports |
| `structure { delegates }` | Component diagram with dependency arrows |
| `skills { }` | Provided interfaces |
| `tools { }` | Required interfaces |
| `routing { }` | Sequence diagram |
| `workflow { + }` | Activity diagram |
| `branch { }` | Decision node in activity diagram |
| `knowledge { }` | Notes / documentation attached to components |

Bidirectional: draw UML → generate DSL, write DSL → visualize as UML.

---

## 22. Roadmap

Notation: `[x]` shipped, `[ ]` planned. Mirrors the README's roadmap so contributors see the same source of truth in either document.

### Phase 1: Core DSL *(Q1 2026)*

- [x] `Agent<IN, OUT>` typed definitions with SRP enforcement — `agent<IN,OUT>("name") { }`
- [x] `Agent.prompt` — base context string for the LLM
- [x] Skills-only execution path — all agents run through `skills { implementedBy { kotlinLambda } }`
- [x] `Skill.description` — sells the skill to the LLM alongside its type signature
- [x] `Skill.knowledge("key", "description") { "..." }` — unlimited named lazy providers; description tells the LLM what the entry contains before it calls it
- [x] `Skill.toLlmDescription()` — auto-generated markdown: `## Skill`, `**Input:**` / `**Output:**` with inline `@Generable` type shape (description + fields + `@Guide` texts), description prose, `**Knowledge:**` index; `llmDescription("...")` override
- [x] `Skill.toLlmContext()` — full context: `toLlmDescription()` + all knowledge entry contents (separator: `--- key ---\ncontent`); loaded lazily
- [x] `Skill.knowledgeTools()` → `List<KnowledgeTool(name, description, call)>` — tools model: LLM reads `description` to decide which entries to pull; each `call()` is lazy
- [x] `@Generable("desc")` / `@Guide` / `@LlmDescription` — runtime reflection: `toLlmDescription()`, `jsonSchema()`, `promptFragment()`, `fromLlmOutput<T>()`, `PartiallyGenerated<T>`; sealed types via `"type"` discriminator
- [x] `toLlmInput(value)` — typed `@Generable` agent input serialized as JSON instead of `toString()` (#937); symmetric with `fromLlmOutput<T>`
- [x] `Pipeline` execution via composed functions — no runtime casts, no reflection
- [x] `then` — sequential pipeline composition with composed execution
- [x] `/` — parallel fan-out with coroutine concurrency
- [x] `*` — forum shorthand (multi-agent debate, last agent is captain)
- [x] `forum { participant(...); captain(...); allowForumReturn(...) }` — explicit forum roles, finalization permissions, concurrent participants, `onMentionEmitted` output tracking
- [x] `.loop {}` — iterative execution with `(OUT) -> IN?` feedback block + `maxIterations` cap
- [x] `.branch {}` — conditional routing on sealed types, composable with `then`
- [x] DDD package structure: `agents_engine.core` (entities) + `agents_engine.composition` (operators)
- [x] Single-placement rule — each agent instance participates in at most one structure
- [x] `model { }` — Ollama backend; `host`, `port`, `temperature`; injectable `ModelClient` for tests; auto-fallback to inline JSON tool-call format for models without native tool support (#706)
- [x] Agentic execution loop — multi-turn tool calling with budget controls (`maxTurns`, `maxToolCalls`, `maxDuration`, `perToolTimeout`, `maxTokens`, `maxConsecutiveSameTool`) + `onToolUse` observability hook (#637, #963, #969)
- [x] `TokenUsage` on `LlmResponse` — `prompt_eval_count` + `eval_count` parsed from Ollama; cumulative across turns, surfaces `BudgetReason.TOKENS` on overrun (#963)
- [x] Skill selection — manual `skillSelection {}` + automatic LLM routing when multiple skills match
- [x] `onSkillChosen { name -> }` — fires when an agent selects a skill to execute
- [x] `onKnowledgeUsed { name, content -> }` — fires when the LLM fetches a knowledge entry (tools model)
- [x] Tool error recovery — `onToolError { invalidArgs / deserializationError / executionError { ... } }` with `RepairResult.Fixed / Retry / Escalated / Unrecoverable`
- [x] `onError { Throwable -> }` — infrastructure-error observability hook (LLM transport, response parse, budget); pure observability — original exception always rethrows; listener exceptions are attached as suppressed (#962)
- [x] `Agent.observe { event -> }` — sealed `PipelineEvent` (`SkillChosen` / `ToolCalled` / `KnowledgeLoaded` / `ErrorOccurred`) bridges the four hooks into one typed stream; composes additively with prior listeners and carries runtime `requestId` / `sessionId` / `manifestHash` for audit correlation (#965, #1913)
- [x] `onBudgetThreshold(threshold) { reason, usedPercent -> }` — pre-cap warning hook; fires once per `BudgetReason` (TURNS / TOOL_CALLS / DURATION / TOKENS) when cumulative usage crosses the configured fraction, before the corresponding cap throws (#966)
- [x] `onBudgetExceeded { reason, currentLimit -> BudgetDecision }` — hard-cap decision hook; `Extend(newLimit)` raises the cap and continues, `Stop` (or no handler / non-greater limit) throws `BudgetExceededException`. Wired for the tool-call cap (#2412)
- [x] `onBefore*` interceptors — `Decision` (`Proceed`, `ProceedWith`, `Deny`, `Substitute`) across `onBeforeSkill`, `onBeforeTurn`, and `onBeforeToolCall`; dynamic policy runs after static allowlist checks and before regular/session-aware tool dispatch (#1907)
- [x] MCP client — `mcp { server() }` agent DSL with HTTP / stdio / TCP transports, Bearer auth, namespacing
- [x] MCP server — `McpServer.from(agent) { expose() }` exposes agent skills as MCP tools; 2025-03-26 spec conformance (ping, capabilities, protocolVersion negotiation, cursor/nextCursor, Content-Type/415, 405 with Allow, Mcp-Session-Id); inbound bearer auth, Host/Origin allowlists, per-principal tool policy, and filtered capability snapshots (#1902)
- [x] MCP runner — `McpRunner.serve(agent, args)` picocli-style one-line `main` for standalone agent JARs
- [x] Memory bank — `MemoryBank`, `memory_read` / `memory_write` / `memory_search` tools with per-skill `useMemory()` opt-in (#856)
- [x] Supply-chain hygiene — pinned Gradle wrapper, dependency-locking via `gradle.lockfile`, `gradle/verification-metadata.xml` SHA-256 verification, `updateVerificationMetadata` cross-platform Gradle task (#858, #872, #883)
- [x] `loadResource(path)` / `loadResourceOrNull(path)` — read agent system prompts from classpath resources; fail-fast at agent construction when path is missing; UTF-8 decoded; leading-slash normalized (#980)
- [x] `LiveShow` / `LiveRunner` — REPL deployment surface mirroring MCP's two-layer split (`LiveShow.from(x).start()` + `LiveRunner.serve(x, args)`). Six factory overloads cover `Agent` / `Pipeline` / `Forum` / `Parallel` / `Loop` / `Branch` (any String-input structure). String-concatenated conversation history with `--- user ---` / `--- assistant ---` delimiters and configurable cap. Built-in `/quit`, `/exit`, `/clear`, `/help` plus user-extensible `slash(name) { }`. `--once "<prompt>"` for non-interactive single-turn use. ANSI color theme, ASCII Agents.KT banner, in-place cat spinner, JLine-backed cursor movement and in-memory arrow-key history for interactive terminals, lifecycle hooks (`onTurnStart` / `onTurnEnd` / `onErrorReported`), `renderOutput` post-processor (#981, #983, #985)
- [x] `Swarm` — ServiceLoader-based agent discovery: each sibling JAR ships a `META-INF/services/agents_engine.runtime.AgentProvider`; the captain calls `Swarm.discover()` and `me.absorb(sibling)` to expose each sibling's `Agent<*, *>` surface as a tool with full personality preserved (prompt, skills, knowledge, memory). In-JVM only (single-classloader); cross-language is MCP's job (#984)
- [x] `wrap` — teacher-student prompt override operator. `teacher wrap student` runs the teacher to compute a system prompt, then invokes the student with that prompt in effect for one call only (baked-in `prompt` is restored after). Two framings: **education** (teacher specializes a generalist student for a task) and **security** (teacher locks down the student's task surface for the call). PRD notation is `>>`; Kotlin doesn't permit user types to overload literal `>>`, so the infix is named `wrap`. Headline test: agent A teaches agent B to compute fib(10) via a `fib` tool driven by a stub `ModelClient` that reads the teacher's instruction from the system prompt (#1698).

### Phase 2: Runtime + Distribution *(Q2 2026)*

**Priority (must-ship):**
- [~] `model { }` — extend beyond Ollama: provider abstraction landed via `ModelProvider`. **Anthropic shipped (#1644)** with the `claude(name)` DSL and `ClaudeClient` mapping `LlmMessage` ↔ Anthropic structured content (`tool_use` / `tool_result`). **OpenAI shipped (#1656)** with the `openai(name)` DSL and `OpenAiClient` mapping to Chat Completions (`tool_calls` ↔ `tool_call_id`, `parameters` schema field). Google (Gemini) and `suspend fun` + Flow streaming still pending.
- [x] Permission manifest / capability graph — `:agents-kt-manifest` adds `permissionManifest { }` on agents and compositions, deterministic JSON/YAML writers, SHA-256 runtime correlation, masked provider secrets, tool-policy capture, high-risk widening verification, and Gradle tasks `agentManifest` / `verifyAgentManifest` (#1912).
- [x] JSONL audit log exporter — `:agents-kt-observability` writes append-only, one-line-per-event rows for `PipelineEvent` and `AgentEvent` with `requestId`, `sessionId`, `manifestHash`, agent/skill/tool ids, event type, timestamp, provider, and model. Size/day rotation is configurable; write failures buffer/drop oldest under backpressure and never throw into the agent path. Raw tool args/results and generated content are omitted by default (#1914).
- [x] Declarative tool sandbox policy DSL — `ToolPolicy` with `risk`, filesystem, network, and environment sub-policies; `tool { policy { ... } }` captures the declaration, manifest map/JSON/YAML helpers round-trip it, and tool audit events surface `toolPolicyRisk` / `usedDeclaredCapability`. Declarative only in 0.6.0; enforcement belongs to the sibling sandbox issue (#1915 / #1916).
- [ ] `Tool<IN, OUT>` base + `McpTool<IN, OUT>` — MCP as native Tool inheritance, not a wrapper (§5.8)
- [ ] MCP client integration — `McpTool` instances consumable alongside local tools
- [ ] `grants { tools(...) }` — Layer 2 permissions use actual `Tool<*,*>` references
- [ ] Permission model: 3 states — Granted (auto-runs), Confirmed (user approval), Absent (unavailable)
- [x] KSP annotation processor for compile-time `@Generable` (replaces runtime reflection); constrained decoding (Ollama/vLLM) + guided JSON mode (Anthropic/OpenAI). **Validation pass shipped (#1700)** — compile-time errors for non-sealed interfaces, annotation classes, enums, abstract classes, classes without a parameterised primary constructor. **Schema-generation pass shipped (#1701)** — `*__GeneratedSchema.kt` per non-sealed `@Generable data class`. **Sealed-root schema gen shipped (#1702)** — `{"oneOf":[...]}` shape with `"type"` discriminators per variant. **`toLlmDescription` codegen shipped (#1703)** — second `LLM_DESCRIPTION` const in the same generated object, byte-identical to the runtime markdown. **`constructFromMap` codegen shipped (#1704)** — generated companion exposes `@JvmStatic fun constructFromMap(Map<*, Any?>): T?` that calls @PublishedApi coercion helpers in `GenerableSupport`. **Phase 3 shipped (#1705)** — `kotlin-reflect` dropped from the consumer-visible runtime classpath via `compileOnly` scope; reflection-using fallback paths wrapped via `ReflectionFallback.withReflection { ... }` for graceful `NoClassDefFoundError` degradation; defensive emission gate skips sealed parents with no visible variants (incremental-compile race). Constrained-decoding integration with provider-side schema endpoints remains the next chunk of work for this PRD line — that's about wiring the already-generated schemas into Ollama's structured-output mode / Anthropic & OpenAI's `tool` JSON-mode rather than further codegen.
- [x] **InternalsAgent — self-hosting docs surface (#1837 + 63 children #1838–#1900).** `buildInternalsAgent(): Agent<String, String>` in `agents_engine.runtime.internals` registers one skill per source file in the framework (~63 today). Each skill is an `implementedBy { _ -> loadResource("internals-agent/<path>.md") }` pure data fetch — no `model { }` configured, because the IDE's LLM does the reasoning. Skills are registered by classpath-scan at construction time (file:// in dev, jar:// in production), deriving names from paths (`internals-agent/core/Agent.md` → `core_agent_kt`); each adjunct begins with a YAML-style `description:` frontmatter that becomes the LLM-facing tool description. `Main.kt` exposes the agent over MCP via `McpServer.from(...)` on port 8765 (default; configurable via `runInternalsAgent --args="<port>"`). Cursor / Claude Desktop / any MCP client points at `http://localhost:8765/mcp` and queries the framework's own internals as tools. Adding a new source file is one `.md` drop-in — no `InternalsAgent.kt` edit needed. See `docs/internals-agent.md` and §5.8.
- [ ] Native CLI binary (GraalVM — no JRE required); `brew`, npm, pip, curl, apt
- [ ] jlink minimal JRE bundle for runtime (~35 MB)
- [ ] Structure-level budgets — `budget { }` on Pipeline / Forum / Parallel / Loop (§5.6)

**Secondary (stretch):**
- [ ] `Prompt<IN, OUT>` entity definition and DSL — typed public interface for agents (§8.6)
- [ ] Prompt → Skill routing via `triggeredBy`, knowledge slot binding and validation (§8.6.4–§8.6.6)
- [ ] Compile-time validations #27–#34 for prompts (§8.6.11)
- [ ] Prompt serialization in agent.json; A2A AgentCard generation from prompts (§8.6.9)
- [ ] Tool constraints: `constraints {}` DSL with `ToolConstraint` sealed hierarchy — visibility control per turn (§5.6)
- [ ] Typed hook payloads: `onSkillStart<T>`, `onToolCall<T>`, `onToolResult<T>` (§8.4)
- [ ] Typed memory strategies: `sliding<T>`, `tokenBudget<T>`, `summarized<T>` namespaces (§8.5)
- [ ] Human-in-the-loop: `confirm()` with message templates, timeouts, fallback behavior (§9.2.1)
- [x] Session model — multi-turn `AgentSession` **shipped** (#1736; `events` Flow + `await()` + snapshot/resume). _Remaining:_ automatic compaction (`SUMMARIZE` / `SLIDING_WINDOW` / `CUSTOM`) (§5.7)
- [ ] Reactive context hooks: `beforeInference`, `afterToolCall` — context-mutating hooks that inject system reminders (§8.4)
- [ ] `.spawn {}` — independent sub-agent lifecycle, `AgentHandle<OUT>`, parent-managed join
- [x] Reactive event stream for UIs **shipped** — `AgentSession.events: Flow<AgentEvent>` (#1736) + `agent.observe { }` (#965). _Remaining:_ composition-stage event types (`StageStarted`, `PipelineCompleted`) at the Pipeline level (§10.2)
- [ ] Serialization — `agent.json`, A2A AgentCard
- [ ] JAR bundles and folder-based assembly
- [ ] Gradle plugin

### Phase 3: Production *(Q3 2026)*

- [ ] Layer 2: Full Structure DSL with delegates, grants, authority, routing, escalation
- [ ] Runtime permission model (§9.2.1)
- [ ] All 37 compile-time validations enforced by the Gradle plugin (§11)
- [ ] AgentUnit testing framework — unit tests, semantic tests (LLM-as-judge), Skill Coverage metrics
- [ ] A2A server + client
- [ ] MCP prompt compatibility — `prompts/list`, `prompts/get` (§8.6.9)
- [ ] Prompt composition operators — `then`, `refine`, `variant` (§8.6.10)
- [ ] Shared prompt libraries — Maven distribution (§8.6.13)
- [ ] File-based knowledge — `skill.md`, `reference`, `examples`, `checklist` + RAG pipeline
- [ ] Custom tool deserializers — per-tool or per-server lambdas mapping raw MCP `content[]` (and future A2A skill outputs) to typed Kotlin values. Composable: default deserializer per `McpClient`, overridable per tool via `mcp.tool("name").withDeserializer<T> { content -> ... }`
- [ ] CLI — `serve`, `inspect`, `validate`, `prompts`
- [ ] Distributed agents framework (§13) — `Agent.fromA2A<>()` typed proxies, locality-transparent pipelines, catalog discovery, placement manifest, schema drift detection
- [x] Production observability foundation — OpenTelemetry traces via `:agents-kt-observability` + `:agents-kt-otel`

### Phase 4: Ecosystem *(Q4 2026)*

- [ ] Team DSL — swarm coordination, message passing (§9.2.2) — if hardware and demand justify
- [ ] Distributed framework — registry-based discovery, Forum/Parallel across nodes, circuit breaker / bulkhead (§13)
- [ ] Knowledge packs — battle-tested prompt libraries for common domains
- [ ] Agent generation from natural language (NL → Kotlin DSL)
- [ ] Skillify — extract reusable skills from session transcripts
- [ ] Visual structure editor; UML bidirectional conversion (Deep-Code.AI integration)
- [ ] Knowledge marketplace
- [ ] Maven Central publishing for agent bundles

---

## 23. Open Questions

1. **Variance rules:** Should `Agent<IN, OUT>` support covariance/contravariance? `Agent<SpecRequest, Specification>` assignable to `Agent<TaskRequest, Specification>`?

2. **Dynamic skill selection:** Can an agent discover which skill to use at runtime via LLM reasoning, or must routing be predefined?

3. **Knowledge versioning:** How to handle knowledge content updates across running JAR instances? Hot-reload vs redeployment.

4. **Cross-structure communication:** A2A protocol, shared message bus, or explicit bridge agents?

5. **Koog interoperability:** Can an Agents.KT agent use Koog internally for behavior graphs within `implementedBy`?

6. **Structure inheritance:** Can one structure extend another with overrides?

7. **Adapter generation:** Can the framework auto-generate adapter agents for type mismatches between JAR versions?

8. **Knowledge embedding cost:** RAG vs full inclusion per skill? Token budget management for large knowledge packs.

9. **Skill selection strategy:** When multiple skills match by input type, should the LLM use `description` + `knowledgeTools()` descriptions to choose, or explicit manual routing? What is the fallback when no LLM is configured? (Current answer: `description` on skills and knowledge entries is implemented; `skillSelection {}` is implemented. The first-match fallback originally shipped here was removed in 0.7.21 (#3087) — ambiguous routing without a selector or model now throws `SkillRoutingException`.)

10. ~~**Knowledge bridging:**~~ **Resolved.** Code-based `knowledge("key") { "..." }` entries are the only knowledge mechanism. `loadFile()` inside the lambda handles file content. No framework-managed file conventions.

11. **Compaction ownership:** Who triggers compaction — the framework automatically, or the agent explicitly? If automatic, how does the agent know context was lost? If explicit, agents must manage their own context budget.

12. **Hook ordering:** When multiple hooks fire on the same event, what order do they execute? Can hooks cancel each other? Can a hook prevent a tool call from executing?

13. **Spawn budget inheritance:** When a parent spawns a child, does the child get a fraction of the parent's remaining budget, its own independent budget, or unlimited until the parent's budget depletes? How are concurrent spawns accounted?

14. **Team message reliability:** In swarm mode, what happens when a message to a team member arrives while that member is mid-inference? Queue until next turn? Inject as system reminder? Drop with notification to sender?

15. **Memory conflict resolution:** When two agents in a team both write to shared memory concurrently, last-write-wins? Merge? Queue? Does the framework even detect conflicts?

16. **MCP schema drift:** When a remote MCP server updates its tool schema and the local `@Generable` wrapper is stale, should the framework fail at startup, warn, or silently adapt? How is schema versioning handled?

17. **MCP tool namespacing:** When multiple MCP servers expose tools with the same name, how are collisions resolved? `server/tool` prefix convention? Explicit aliasing in `mcp {}` block?

---

## 24. Success Metrics

| Metric | 6 months | 12 months |
|--------|----------|-----------|
| GitHub stars | 500+ | 3,000+ |
| Monthly downloads (all channels) | 1,000+ | 10,000+ |
| Contributors | 5+ | 25+ |
| Production deployments | 3+ | 20+ |
| Compile-time errors caught | 100+ | 1,000+ |
| A2A agent deployments | 5+ | 50+ |
| Published knowledge packs | 10+ | 100+ |
| Agent bundles on Maven | 20+ | 200+ |
| AgentUnit tests in community | 500+ | 5,000+ |
| npm weekly downloads | 200+ | 2,000+ |
| pip weekly downloads | 200+ | 2,000+ |
| brew installs | 100+ | 1,000+ |
| Docker pulls | 500+ | 5,000+ |
| Documentation pages | 50+ | 150+ |

---

*Agents.KT — Define Freely. Compose Strictly. Ship Reliably.*
