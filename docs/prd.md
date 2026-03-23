# Agents.KT

## Typed Kotlin DSL Framework for AI Agent Systems

### *Define Freely. Compose Strictly. Ship Reliably.*

---

**Product Requirements Document — Version 1.4**
**March 2026 · CONFIDENTIAL**

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
A * B           : Agent<X,Y> * Agent<*,Z> → Forum<X, Z>  (first's IN, last answers)
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

Tools declared in `implementedBy { tools(...) }` are the **only** tools the LLM can call. Unknown tool calls are rejected with an error message to the LLM, not silently ignored:

```
Tool 'delete_file' is not available. Available: write_file, compile, run_tests
```

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

**`onSkillChosen`** fires once per invocation when the agent picks a skill — either via `skillSelection {}` predicates or LLM decision. Useful for routing visibility in multi-skill agents.

**`onKnowledgeUsed`** fires each time the LLM calls a knowledge tool (Model B — lazy loading). Does *not* fire for eager loading (`toLlmContext()`), since all entries are pre-loaded into the system prompt. Does *not* fire for action tools.

**`onToolUse`** fires after every action tool execution. Useful for logging, tracing, cost tracking, and test assertions.

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

---

## 6. Skill Model: Independent Typed Functions

A skill is an independently typed function `Skill<IN, OUT>` — it is **not** locked to the agent's type contract. An agent is a container of skills, each with its own `<IN, OUT>`. The only constraint: **at least one skill must produce the agent's `OUT` type.** This is validated at agent construction time.

Every skill has a **mandatory `description`** — a short text that "sells" the skill to the LLM alongside its type signature, enabling the LLM to choose the right skill for the job. Skills also carry unlimited named **`knowledge` entries**: lazy `() -> String` providers that supply context to the LLM when the skill is selected.

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
│  ├── description — "sells" skill to LLM   │  ← mandatory, implemented
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
│  ├── forum {}    — agents discuss + converge │
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

When an agent has multiple skills, selection happens in one of two ways:

**LLM decides** (default) — the LLM reads each skill's `description` and `knowledgeTools()` descriptions, then chooses. This is the natural path when `model {}` is configured.

**Predicate-based** — explicit Kotlin predicates when deterministic routing is needed:

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

A skill can be implemented by **anything that transforms IN to OUT**: tools, agents, pipelines, forums (multi-agent discussion), conditional branches, or any combination.

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

Think **jury deliberation** — the case (IN) is dropped on the table, jurors discuss and see each other's reasoning across rounds, and one agent delivers the verdict (OUT). Convention: the last agent in the `*` chain is the foreperson.

Forum typing: **first agent's IN** determines the input, **last agent's OUT** (captain) determines the output. Agents in between can have any types — they're participants in a discussion, not a pipeline.

```kotlin
// Forum: first's IN = Specs, captain's OUT = Result
val codeDiscussion = opinionsArbitrageMaster * crazyGenerator * passiveGenerator * answerMaster
// Forum<Specs, Result>

// Compose with pipeline: converter feeds the forum
val pipeline = inputToSpecsConverter then (opinionsArbitrageMaster * crazyGenerator * passiveGenerator * answerMaster)
// Pipeline<Input, Result>

skill("reliable-write") {
    implementedBy {
        forum(maxRounds = 3) {
            agent(kotlinExpert)          // Specification → Opinion
            agent(javaConverter)         // Specification → Opinion
            agent(arbiter)              // Opinions → FinalCode  ← captain
        }
        // Agents see each other's outputs, discuss across rounds
        // Last agent is the captain — delivers the final answer
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

The distinction from Forum: parallel agents do **not** see each other's outputs — each runs in isolation on the same input. Forum agents collaborate across rounds.

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
            then forum(maxRounds = 2) {    // reviewers discuss
                agent(reviewer1)
                agent(reviewer2)
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
  Forum IN = a.IN, Forum OUT = c.OUT (captain), middle agents any types

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
| `*` | Forum (discuss + converge) | First's `IN`, last's `OUT` (captain) | `Forum<first.IN, last.OUT>` |
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

This event hierarchy is the telemetry backbone. OpenTelemetry traces (future) map events to spans with a nested hierarchy: `pipeline → stage → agent → skill → tool → llm_call`. Each span carries token usage and cost attributes for budget attribution across multi-agent pipelines.

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

For perspectives that need to see each other's reasoning (debate, not parallel), use `*` (forum):

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
| **Debate** | `*` forum | Agents see each other's reasoning |
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
| 25 | **Skills** | Every skill must have a non-empty `description` | Error |
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

---

## 13. Distribution

Agent distribution (JAR bundles, folder-based assembly, hot deploy, ClassLoader isolation) is planned for Phase 3+. Current development focuses on the DSL, type system, and runtime engine.

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

## 17. Project Structure

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

## 18. Competitive Landscape

- **LangChain (Python)** — largest ecosystem, 100x community, no typed contracts, no compile-time validation
- **CrewAI (Python)** — fast to prototype, role-based agents, no type safety, flat architecture
- **Koog (Kotlin, JetBrains)** — Kotlin-native, multiplatform, behavior graphs; tool deserialization breaks in practice; no typed pipeline composition
- **Mastra (TypeScript)** — visual builder, model router, good DX; no hierarchical delegation, no permission model
- **Pydantic AI (Python)** — typed Python with validation, growing fast; no composition operators, no knowledge model
- **AutoGen (Python, Microsoft)** — strong multi-agent conversation; complex API, no typed contracts
- **Semantic Kernel (C#/Python, Microsoft)** — enterprise, planner architecture; no agent composition, heavy abstraction

**Agents.KT positioning:** typed `Agent<IN, OUT>` contracts, `@Generable` guided generation, `Tool<IN, OUT>` with MCP inheritance, fractal composition operators (`then`/`*`/`/`/`.loop`/`.branch`), code-based skill knowledge with two delivery models. The gap we fill: no framework connects typed contracts → composition validation → MCP-native tools → knowledge delivery in one coherent system.

---

## 19. Full Example: Deep-Code.AI

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

## 20. UML Isomorphism (Deep-Code.AI Integration)

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

## 21. Roadmap

### Phase 1: Core DSL (Q1 2026)

**Implemented:**
- `Agent<IN, OUT>` typed definitions with SRP enforcement — `agent<IN,OUT>("name") { }`
- `Agent.prompt` — base context string for the LLM
- Skills-only execution path — all agents run through `skills { }`, `implementedBy { kotlinLambda }`
- `Skill.description` (mandatory) — sells the skill to the LLM alongside its type signature
- `Skill.knowledge("key", "description") { "..." }` — unlimited named lazy providers; description tells LLM what the entry contains before it calls it
- `Skill.toLlmDescription()` — auto-generated markdown: `## Skill`, `**Input:**`/`**Output:**` with inline `@Generable` type shape (description + fields + `@Guide` texts), description prose, `**Knowledge:**` index; override with `llmDescription("...")` when needed
- `Skill.toLlmContext()` — full context: `toLlmDescription()` + all knowledge entry contents (separator: `--- key ---\ncontent`); loaded lazily
- `Skill.knowledgeTools()` → `List<KnowledgeTool(name, description, call)>` — tools model: LLM reads `description` to decide which entries to pull; each `call()` is lazy
- `@Generable("desc")` / `@Guide` / `@LlmDescription` — runtime reflection: `toLlmDescription()` (convention-over-configuration markdown for any `@Generable` class), `jsonSchema()`, `promptFragment()`, `fromLlmOutput<T>()`, `PartiallyGenerated<T>`; sealed types via `"type"` discriminator
- `Pipeline` execution via composed functions — no runtime casts, no reflection
- Composition operators: `then` (pipeline), `*` (forum), `/` (parallel), `.loop {}` (iterative + plain `while`), `.branch {}` (sealed type routing)
- DDD package structure: `agents_engine.core` (entities) + `agents_engine.composition` (operators)
- Single-placement rule: each agent instance participates in at most one structure
- `model { }` — Ollama backend; `host`, `port`, `temperature`; injectable `ModelClient` for tests
- Agentic execution loop — multi-turn tool calling with budget controls (`maxTurns`) + `onToolUse` observability callback
- `onSkillChosen { name -> }` — fires when agent selects a skill to execute
- `onKnowledgeUsed { name, content -> }` — fires when LLM fetches a knowledge entry (tools model)

**Planned:**
- `model { }` — extend to multi-provider (Anthropic, OpenAI, Google) via ChatModel abstraction
- KSP annotation processor for compile-time `@Generable` schema generation; constrained decoding (Ollama) + guided JSON mode (Anthropic/OpenAI) enforcement tiers
- Skill routing: predefined rules + `RoutingStrategy.LLM_DECISION`
- Layer 2: Structure DSL with delegates, grants, authority, routing, escalation
- All validations from §11 catalog
- CLI: `agents new`, `generate`, `validate`
- Project structure conventions

### Phase 2: Runtime + Distribution (Q2 2026)

**Priority (must-ship):**
- `model { }` — extend beyond Ollama: ChatModel interface, provider abstraction (Anthropic, OpenAI, Google), `suspend fun` + Flow streaming
- Agentic execution loop: extend budget controls (`maxToolCalls`, `maxTokens`, `maxTime`) + structure-level budgets (§5.6)
- `Tool<IN, OUT>` base + `McpTool<IN, OUT>` with MCP client connectivity (§5.8)
- `onError` callback for infrastructure error handling
- KSP annotation processor for compile-time `@Generable` (replaces runtime reflection)
- Constrained decoding (Ollama/vLLM) + guided JSON mode (Anthropic/OpenAI)

**Secondary (stretch):**
- Tool constraints: `constraints {}` DSL with `ToolConstraint` sealed hierarchy — visibility control per turn (§5.6)
- Typed hook payloads: `onSkillStart<T>`, `onToolCall<T>`, `onToolResult<T>` (§8.4)
- Typed memory strategies: `sliding<T>`, `tokenBudget<T>`, `summarized<T>` namespaces (§8.5)
- Human-in-the-loop: `confirm()` with message templates, timeouts, fallback behavior (§9.2.1)
- Session model: multi-turn conversation, compaction strategies (§5.7)
- Reactive context hooks: `beforeInference`, `afterToolCall`, `onBudgetThreshold` (§8.4)
- Skill routing: predefined rules + `RoutingStrategy.LLM_DECISION`
- MCP server: expose agents as MCP endpoints (§5.8)
- Pipeline observability: `observe {}`, `Flow<PipelineEvent>` (§10.2)
- Forum discussion rounds and Parallel coroutine execution

### Phase 3: Production (Q3 2026)

- Agent memory: project/user/global scopes (§8.5)
- `.spawn {}` operator: independent sub-agent lifecycle (§10.1)
- Layer 2: Structure DSL with delegates, grants, authority, routing
- Runtime permission model (§9.2.1)
- A2A server + client
- JAR distribution: agent bundles, assembly engine, Gradle plugin
- CLI: `serve`, `inspect`, `validate`
- GraalVM native binary + jlink runtime

### Phase 4: Ecosystem (Q4 2026)

- Team DSL: swarm coordination, message passing (§9.2.2) — if hardware and demand justify
- AgentUnit: semantic tests, LLM-as-judge, skill coverage
- Knowledge packs: battle-tested prompt libraries
- Visual structure editor
- UML bidirectional conversion (Deep-Code.AI integration)
- Maven Central publishing for agent bundles
- Production observability: OpenTelemetry traces

---

## 22. Open Questions

1. **Variance rules:** Should `Agent<IN, OUT>` support covariance/contravariance? `Agent<SpecRequest, Specification>` assignable to `Agent<TaskRequest, Specification>`?

2. **Dynamic skill selection:** Can an agent discover which skill to use at runtime via LLM reasoning, or must routing be predefined?

3. **Knowledge versioning:** How to handle knowledge content updates across running JAR instances? Hot-reload vs redeployment.

4. **Cross-structure communication:** A2A protocol, shared message bus, or explicit bridge agents?

5. **Koog interoperability:** Can an Agents.KT agent use Koog internally for behavior graphs within `implementedBy`?

6. **Structure inheritance:** Can one structure extend another with overrides?

7. **Adapter generation:** Can the framework auto-generate adapter agents for type mismatches between JAR versions?

8. **Knowledge embedding cost:** RAG vs full inclusion per skill? Token budget management for large knowledge packs.

9. **Skill selection strategy:** When multiple skills match by input type, should the LLM use `description` + `knowledgeTools()` descriptions to choose, or explicit predicates? What is the fallback when no LLM is configured? (Partial answer: `description` on skills and knowledge entries is implemented; predicate-based `skillSelection {}` planned.)

10. ~~**Knowledge bridging:**~~ **Resolved.** Code-based `knowledge("key") { "..." }` entries are the only knowledge mechanism. `loadFile()` inside the lambda handles file content. No framework-managed file conventions.

11. **Compaction ownership:** Who triggers compaction — the framework automatically, or the agent explicitly? If automatic, how does the agent know context was lost? If explicit, agents must manage their own context budget.

12. **Hook ordering:** When multiple hooks fire on the same event, what order do they execute? Can hooks cancel each other? Can a hook prevent a tool call from executing?

13. **Spawn budget inheritance:** When a parent spawns a child, does the child get a fraction of the parent's remaining budget, its own independent budget, or unlimited until the parent's budget depletes? How are concurrent spawns accounted?

14. **Team message reliability:** In swarm mode, what happens when a message to a team member arrives while that member is mid-inference? Queue until next turn? Inject as system reminder? Drop with notification to sender?

15. **Memory conflict resolution:** When two agents in a team both write to shared memory concurrently, last-write-wins? Merge? Queue? Does the framework even detect conflicts?

16. **MCP schema drift:** When a remote MCP server updates its tool schema and the local `@Generable` wrapper is stale, should the framework fail at startup, warn, or silently adapt? How is schema versioning handled?

17. **MCP tool namespacing:** When multiple MCP servers expose tools with the same name, how are collisions resolved? `server/tool` prefix convention? Explicit aliasing in `mcp {}` block?

---

## 23. Success Metrics

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