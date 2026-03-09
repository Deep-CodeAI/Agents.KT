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
skill.toLlmDescription()   // compact: name + type signature + description
skill.toLlmContext()        // all-at-once: description + all knowledge entries merged
skill.knowledgeTools()      // tools model: knowledge as a callable list the LLM pulls on demand
```

**`toLlmDescription()`** — what the LLM reads when scanning available skills:

```
Skill: write-code | Specification → CodeBundle
Writes production Kotlin code from scratch based on a specification
```

**`toLlmContext()`** — everything pre-loaded before the LLM runs. Knowledge descriptions appear as section headers:

```
Skill: write-code | Specification → CodeBundle
Writes production Kotlin code from scratch based on a specification

Knowledge:
--- style-guide: Preferred coding style — immutability, naming, formatting ---
Prefer val over var. Use data classes for DTOs.
--- examples: Concrete input/output pairs for few-shot prompting ---
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
- [x] `Skill.toLlmDescription()` / `toLlmContext()` / `knowledgeTools()` — two LLM context models
- [x] `KnowledgeTool(name, description, call)` — tools model with lazy per-entry loading
- [x] `then` — sequential pipeline with composed execution (no runtime casts)
- [x] `/` — parallel fan-out
- [x] `*` — forum (multi-agent discussion)
- [x] Single-placement enforcement across all structure types
- [x] `.loop {}` — iterative execution with `(OUT) -> IN?` feedback block
- [x] `.branch {}` — conditional routing on sealed types, composable with `then`
- [ ] `model { }` — LLM inference path with tool-calling
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
