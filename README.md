# Agents.KT

**Typed Kotlin DSL framework for AI agent systems.**

*Define Freely. Compose Strictly. Ship Reliably.*

---

Every agent is `Agent<IN, OUT>`. One input type, one output type, one job. Type mismatches and wrong compositions are caught by the compiler. Reused agent instances are caught at construction time.

```kotlin
val parse = agent<RawText, Specification>("parse") {
    skills {
        skill<RawText, Specification>("parse-spec", "Splits raw text into a structured specification") {
            implementedBy { input -> Specification(input.text.split(",").map { it.trim() }) }
        }
    }
}
val generate = agent<Specification, CodeBundle>("generate") {
    skills {
        skill<Specification, CodeBundle>("gen-code", "Generates stub functions for each endpoint") {
            implementedBy { spec -> CodeBundle(spec.endpoints.joinToString("\n") { "fun $it() {}" }) }
        }
    }
}
val review = agent<CodeBundle, ReviewResult>("review") {
    skills {
        skill<CodeBundle, ReviewResult>("review-code", "Approves code if it is non-empty") {
            implementedBy { code -> ReviewResult(approved = code.source.isNotBlank()) }
        }
    }
}

// Compiler checks every boundary
val pipeline = parse then generate then review
// Pipeline<RawText, ReviewResult>

val result = pipeline(RawText("getUsers, createUser, deleteUser"))
// ReviewResult(approved=true)
```

---

## Why Agents.KT

Most agent frameworks let you wire anything to anything. Agents.KT says no.

| Problem | Agents.KT answer |
|---------|-----------------|
| God-agents with unlimited responsibilities | `Agent<IN, OUT>` — one type contract, compiler-enforced SRP |
| Runtime type mismatches between agents | `then` requires `A.OUT == B.IN` — compile error otherwise |
| The same agent instance wired into two places | Single-placement rule — `IllegalArgumentException` at construction time |
| LLM doesn't know which skill to use | Every skill has a mandatory `description` — the LLM reads it to choose |
| LLM doesn't know what context to load | `knowledge("key", "description") { }` entries — LLM reads descriptions before deciding to call |
| Flat pipelines only | Five composition operators covering sequential, parallel, iterative, branching, and multi-agent patterns |
| LLM output is an untyped string | `@Generable` + `@Guide` — `toLlmDescription()`, JSON Schema, prompt fragment, lenient deserializer, and `PartiallyGenerated<T>` via runtime reflection; KSP compile-time generation planned Phase 2 |
| No testing story | AgentUnit — deterministic through semantic assertions *(planned)* |
| JVM frameworks require Java installed | Native CLI binary via GraalVM *(planned)* |

---

## Skills

An agent is a container of typed skills. Each skill has its own `<IN, OUT>` and a mandatory `description`. At least one skill must produce the agent's `OUT` type — validated at construction.

```kotlin
val writeCode = skill<Specification, CodeBundle>("write-code",
    "Writes production Kotlin code from scratch based on a specification") {
    knowledge("style-guide", "Preferred coding style — immutability, naming, formatting") {
        "Prefer val over var. Use data classes for DTOs."
    }
    knowledge("examples", "Concrete input/output pairs for few-shot prompting") {
        loadExamples("code/greenfield-examples.kt")
    }
    implementedBy { spec -> CodeBundle(generate(spec)) }
}

val coder = agent<Specification, CodeBundle>("coder") {
    prompt("You are an expert Kotlin developer.")
    skills {
        +writeCode                                                          // pre-defined skill
        skill<String, String>("format-code", "Formats Kotlin source") { }  // inline utility skill
    }
}

// Call any skill directly — fully typed, no casts
writeCode.execute(mySpec)   // Specification → CodeBundle
```

### How a skill describes itself to the LLM

```kotlin
skill.toLlmDescription()   // auto-generated markdown — no extra annotations needed
skill.toLlmContext()        // full context: description + all knowledge content loaded
skill.knowledgeTools()      // tools model: knowledge as callable list the LLM pulls on demand
```

**`toLlmDescription()`** — convention-over-configuration. Auto-generated from what's already on the skill — name, types, description, and knowledge index. No `input()`, `output()`, or `rule()` calls needed. When `IN`/`OUT` types carry `@Generable`, their description and field list (with `@Guide` texts) are embedded inline:

```markdown
## Skill: write-code

**Input:** Specification — A structured API specification
  - endpoints (List<String>): List of endpoint paths to implement
**Output:** CodeBundle — A bundle of generated Kotlin source files
  - source (String): The generated Kotlin source code

Writes production Kotlin code from scratch.

**Knowledge:**
- style-guide — Preferred coding style — immutability, naming, formatting
- examples — Concrete input/output pairs for few-shot prompting
```

Override for the rare case where generated text isn't right:

```kotlin
skill<Specification, CodeBundle>("write-code", "...") {
    llmDescription("Custom markdown description")
}
```

**`toLlmContext()`** — everything pre-loaded before the LLM runs: `toLlmDescription()` followed by the full content of each knowledge entry:

```markdown
## Skill: write-code

**Input:** Specification — A structured API specification
  - endpoints (List<String>): List of endpoint paths to implement
**Output:** CodeBundle — A bundle of generated Kotlin source files
  - source (String): The generated Kotlin source code

Writes production Kotlin code from scratch.

**Knowledge:**
- style-guide — Preferred coding style — immutability, naming, formatting
- examples — Concrete input/output pairs for few-shot prompting

Knowledge:
--- style-guide ---
Prefer val over var. Use data classes for DTOs.
--- examples ---
...
```

**`knowledgeTools()`** — tools model for LLMs with tool-calling support. The LLM reads `description` to decide which entries to load; nothing is fetched until `call()` is invoked:

```kotlin
data class KnowledgeTool(
    val name: String,
    val description: String,   // LLM reads this to decide whether to call
    val call: () -> String,    // lazy — loads only when invoked
)

val tools = writeCode.knowledgeTools()
// [KnowledgeTool("style-guide", "Preferred coding style...", ...),
//  KnowledgeTool("examples",    "Concrete input/output pairs...", ...)]

tools.find { it.name == "style-guide" }?.call()
// → "Prefer val over var. Use data classes for DTOs."
```

| Model | API | When to use |
|-------|-----|-------------|
| All-at-once | `toLlmContext()` | Small knowledge, any LLM |
| Tools | `knowledgeTools()` | Large knowledge, LLM with tool-calling support |

---

## Guided Generation

Agent inputs and outputs are data classes. `@Generable` + `@Guide` make them LLM-parseable — no manual schema authoring, no runtime boilerplate.

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

**Three annotations:**

- **`@Generable("description")`** — marks the class as an LLM generation target. The optional description appears in auto-generated skill and type documentation.
- **`@Guide("description")`** — per-field (or per sealed variant) guidance for the LLM: range, format, constraints.
- **`@LlmDescription("...")`** — overrides the auto-generated `toLlmDescription()` verbatim for the rare case where the generated text doesn't fit.

Five artifacts are available at runtime via reflection:

| Artifact | API | Use |
|----------|-----|-----|
| LLM description | `ReviewResult::class.toLlmDescription()` | Convention-over-configuration markdown: class name, description, fields, types, `@Guide` texts |
| JSON Schema | `ReviewResult::class.jsonSchema()` | Constrained decoding (Ollama) or JSON mode (Anthropic) |
| Prompt fragment | `ReviewResult::class.promptFragment()` | Injected into system prompt automatically |
| Lenient deserializer | `fromLlmOutput<ReviewResult>(String)` | Parses partial/malformed LLM output; `null` on unrecoverable input |
| Streaming variant | `PartiallyGenerated<ReviewResult>` | Immutable accumulator; `withField()` returns a new copy as tokens arrive |

**`toLlmDescription()`** — auto-generated markdown, no extra work needed:

```markdown
## ReviewResult

Overall quality assessment of a code review

- **score** (Double): Overall score from 0.0 to 1.0. Strict: < 0.6 means fail.
- **verdict** (String): One-sentence verdict: 'approved', 'needs revision', or 'rejected'.
- **issues** (List<String>): Ordered list of specific issues found, or empty if approved.
```

When a skill's `IN`/`OUT` type carries `@Generable`, `Skill.toLlmDescription()` embeds the type shape inline — the LLM sees field names, types, and `@Guide` texts without any extra configuration.

**Two enforcement tiers — chosen at runtime based on the configured model:**

| Tier | Models | How |
|------|--------|-----|
| **Constrained** | Ollama, llama.cpp, vLLM | Grammar-constrained decoding — always valid JSON |
| **Guided** | Anthropic, OpenAI, Gemini | JSON mode + prompt fragment + lenient parse + fallback |

**Sealed types** — `@Guide` on each variant tells the LLM when to use it:

```kotlin
@Generable
sealed interface ReviewDecision {
    @Guide("Use when code passes all checks")
    data class Approved(val score: Double) : ReviewDecision

    @Guide("Use when code has fixable issues — list them in order of severity")
    data class NeedsRevision(
        @Guide("Specific issues, most critical first")
        val issues: List<String>
    ) : ReviewDecision

    @Guide("Use when fundamental design must change before any fix applies")
    data class Rejected(val reason: String) : ReviewDecision
}
```

The lenient deserializer routes to the correct subtype via the `"type"` discriminator. `.branch {}` receives exhaustively matched variants — no boilerplate.

**Streaming:**

```kotlin
val stream: Flow<PartiallyGenerated<ReviewResult>> = reviewer.stream(code)
stream.collect { partial ->
    partial.verdict?.let { showVerdict(it) }   // non-null = field has arrived
    partial.score?.let   { updateScore(it) }
}
```

---

## Composition Operators

### `then` — Sequential Pipeline

```kotlin
val pipeline = specMaster then coder then reviewer
// Pipeline<TaskRequest, ReviewResult>

val full = (specMaster then coder) then (reviewer then deployer)
```

### `/` — Parallel Fan-Out

All agents receive the same input independently. The next stage receives `List<OUT>`.

```kotlin
val parallel = securityReview / styleReview / performanceReview
// Parallel<CodeBundle, Review>

val synthesizer = agent<List<Review>, Report>("synthesizer") {
    skills {
        skill<List<Review>, Report>("merge", "Merges all review results into a single report") {
            implementedBy { reviews ->
                Report(passed = reviews.all { it.passed }, summary = reviews.joinToString("\n") { it.summary })
            }
        }
    }
}

val pipeline = coder then parallel then synthesizer
// Pipeline<Specification, Report>
```

**Liskov:** declare agents as the common supertype — subtypes flow through transparently.

```kotlin
sealed interface Review
data class QuickReview(val summary: String)                        : Review
data class DeepReview(val issues: List<String>, val score: Double) : Review

val quick = agent<CodeBundle, Review>("quick") { skills { skill<CodeBundle, Review>("q", "Quick scan") { implementedBy { QuickReview(briefScan(it)) } } } }
val deep  = agent<CodeBundle, Review>("deep")  { skills { skill<CodeBundle, Review>("d", "Deep scan") { implementedBy { DeepReview(fullScan(it), score(it)) } } } }

val pipeline = (quick / deep) then synthesizer
// Pipeline<CodeBundle, Report>
```

### `*` — Forum (Multi-Agent Discussion)

Think *jury deliberation* — the case lands on the table, agents discuss across rounds, the last agent (foreperson) delivers the verdict. Agents see each other's reasoning; parallel agents do not.

```kotlin
val forum = initiator * analyst * critic * captain
// Forum<Specs, Decision>

val pipeline = inputConverter then forum then formatter
// Pipeline<Input, FormattedDecision>
```

### `.loop {}` — Iterative Execution

The block receives the output and returns the next input to continue, or `null` to stop. Fully composable.

```kotlin
val refineLoop = refine.loop { result -> if (result.score >= 90) null else result }

val qualityLoop = (generate then evaluate).loop { result ->
    if (result.quality >= 90f) null else result.spec
}

val pipeline = prepare then qualityLoop then publish
```

**Quality gate with `while`** — agents and pipelines are plain callable functions; standard Kotlin control flow works without any DSL:

```kotlin
var specs   = SpecsParcel(description = "build a user API")
var quality = 0f
while (quality < 90f) {
    specs   = specPipeline(specs)
    quality = specsEvaluator(specs)
}
```

### `.branch {}` — Conditional Routing on Sealed Types

Routes the output of an agent to a different handler per sealed variant. All branches must produce the same `OUT` type. Unhandled variants throw at invocation.

```kotlin
sealed interface ReviewResult
data class Passed(val score: Double)           : ReviewResult
data class Failed(val issues: List<String>)    : ReviewResult
data class NeedsRevision(val feedback: String) : ReviewResult

val afterReview = reviewer.branch {
    on<Passed>()        then deployer
    on<Failed>()        then failReporter
    on<NeedsRevision>() then (reviser then reviewer)  // pipeline on a variant
}
// Branch<CodeBundle, Report>

val pipeline = coder then afterReview then notifier
// Pipeline<Specification, Notification>
```

---

## Single-Placement Rule

Each `agent<>()` call is an instance. An instance can only be placed in one structure, ever.

```kotlin
val a = agent<A, B>("a") {}
val b = agent<B, C>("b") {}

a then b  // ✅ "a" placed in pipeline

a then c  // ❌ IllegalArgumentException:
          //    Agent "a" is already placed in pipeline.
          //    Create a new instance for "pipeline".

a * forum // ❌ same instance, different structure — also caught
```

---

## Type Algebra

```
Agent<A, B>    : A → B
A then B       : Agent<X,Y> then Agent<Y,Z>    → Pipeline<X,Z>
A / B          : Agent<X,Y> / Agent<X,Y>       → Parallel<X,Y>  →  List<Y> to next
A * B          : Agent<X,Y> * Agent<*,Z>       → Forum<X,Z>
A.loop { }     : (Pipeline<X,Y> | Agent<X,Y>)  → Loop<X,Y>   (null = stop, X = continue)
A.branch { }   : Agent<X, Sealed<Y>)           → Branch<X,Z>  (all variants → same Z)
```

---

## Getting Started

> Agents.KT is in early development. The DSL and composition layer are implemented and tested. Execution engine, CLI, and distribution are on the roadmap.

**Requirements:** JDK 21+, Kotlin 2.x, Gradle

```kotlin
// build.gradle.kts — coming soon via Maven Central
dependencies {
    implementation("dev.agentskt:agents-core:0.1.0")
}
```

For now, clone and run:

```bash
git clone https://github.com/kskobeltsyn/agents-kt
cd agents-kt
./gradlew test
```

---

## Roadmap

**Phase 1 — Core DSL** *(in progress)*
- [x] `Agent<IN, OUT>` with SRP enforcement
- [x] `Agent.prompt` — base context string for the LLM
- [x] Skills-only execution — all agents run through `skills { implementedBy { } }`
- [x] `Skill.description` (mandatory) — sells the skill to the LLM alongside its type signature
- [x] `Skill.knowledge("key", "description") { }` — named lazy context providers per skill
- [x] `Skill.toLlmDescription()` — auto-generated markdown (name, types, description, knowledge index); `llmDescription("...")` override
- [x] `Skill.toLlmContext()` — full context: description markdown + all knowledge content
- [x] `Skill.knowledgeTools()` / `KnowledgeTool(name, description, call)` — tools model with lazy per-entry loading
- [x] `then` — sequential pipeline with composed execution (no runtime casts)
- [x] `/` — parallel fan-out
- [x] `*` — forum (multi-agent discussion)
- [x] Single-placement enforcement across all structure types
- [x] `.loop {}` — iterative execution with `(OUT) -> IN?` feedback block
- [x] `.branch {}` — conditional routing on sealed types, composable with `then`
- [ ] `model { }` — LLM inference path with tool-calling
- [x] `@Generable("desc")` / `@Guide` / `@LlmDescription` — runtime reflection: `toLlmDescription()`, `jsonSchema()`, `promptFragment()`, `fromLlmOutput<T>()`, `PartiallyGenerated<T>`; KSP compile-time generation + constrained decoding (Ollama) planned Phase 2
- [ ] Skill routing strategy (predefined rules + `LLM_DECISION`)
- [ ] `>>` — security/education wrap

**Phase 2 — Runtime** *(Q2 2026)*
- [ ] Detekt custom rule — static detection of reused agent instances
- [ ] Forum discussion rounds
- [ ] Parallel coroutine execution
- [ ] File-based knowledge: `skill.md`, `reference`, `examples`, `checklist`
- [ ] Serialization — `agent.json`, A2A AgentCard
- [ ] JAR bundles and folder-based assembly
- [ ] Gradle plugin

**Phase 3 — Distribution** *(Q3 2026)*
- [ ] Native CLI binary (GraalVM — no JRE required)
- [ ] `brew install agentskt`, npm, pip, curl, apt
- [ ] AgentUnit testing framework
- [ ] A2A protocol support
- [ ] MCP tool integration

---

## License

MIT — K.Skobeltsyn Studio
