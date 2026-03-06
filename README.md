# Agents.KT

**Typed Kotlin DSL framework for AI agent systems.**

*Define Freely. Compose Strictly. Ship Reliably.*

---

Every agent is `Agent<IN, OUT>`. One input type, one output type, one job. Type mismatches and wrong compositions are caught by the compiler. Reused agent instances are caught at construction time — a Detekt rule or compiler plugin for static detection is on the roadmap.

```kotlin
val parse = agent<RawText, Specification>("parse") {
    execute { input -> Specification(input.text.split(",").map { it.trim() }) }
}
val generate = agent<Specification, CodeBundle>("generate") {
    execute { spec -> CodeBundle(spec.endpoints.joinToString("\n") { "fun $it() {}" }) }
}
val review = agent<CodeBundle, ReviewResult>("review") {
    execute { code -> ReviewResult(approved = code.source.isNotBlank()) }
}

// Compiler checks every boundary
val pipeline = parse then generate then review
// Pipeline<RawText, ReviewResult>

// Run it
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
| Flat pipelines only | Four composition operators covering sequential, parallel, and multi-agent patterns |
| No testing story | AgentUnit — deterministic through semantic assertions *(planned)* |
| JVM frameworks require Java installed | Native CLI binary via GraalVM *(planned)* |

---

## Composition Operators

### `then` — Sequential Pipeline

```kotlin
val pipeline = specMaster then coder then reviewer
// Pipeline<TaskRequest, ReviewResult>

// Pipelines compose too
val full = (specMaster then coder) then (reviewer then deployer)
```

### `/` — Parallel Fan-Out

All agents receive the same input independently. The next stage receives `List<OUT>` — one result per agent.

```kotlin
val securityReview  = agent<CodeBundle, Review>("security")  { execute { reviewSecurity(it) } }
val styleReview     = agent<CodeBundle, Review>("style")     { execute { reviewStyle(it) } }
val performanceReview = agent<CodeBundle, Review>("perf")    { execute { reviewPerf(it) } }

val parallel = securityReview / styleReview / performanceReview
// Parallel<CodeBundle, Review>

// The gathering agent receives List<Review> — one entry per parallel agent
val synthesizer = agent<List<Review>, Report>("synthesizer") {
    execute { reviews ->
        Report(
            passed  = reviews.all { it.passed },
            summary = reviews.joinToString("\n") { it.summary },
        )
    }
}

val pipeline = coder then parallel then synthesizer
// Pipeline<Specification, Report>

val report = pipeline(spec)
// Every reviewer ran; synthesizer received all three results
```

**Liskov:** declare agents as the common supertype — subtypes flow through transparently.

```kotlin
sealed interface Review
data class QuickReview(val summary: String)                      : Review
data class DeepReview(val issues: List<String>, val score: Double) : Review

val quick = agent<CodeBundle, Review>("quick") { execute { QuickReview(briefScan(it)) } }
val deep  = agent<CodeBundle, Review>("deep")  { execute { DeepReview(fullScan(it), score(it)) } }

val parallel    = quick / deep          // Parallel<CodeBundle, Review>
val synthesizer = agent<List<Review>, Report>("synth") { execute { merge(it) } }

val pipeline = parallel then synthesizer
// Pipeline<CodeBundle, Report>
```

### `*` — Forum (Multi-Agent Discussion)

Think *jury deliberation* — the case lands on the table, jurors discuss across rounds, one agent delivers the verdict. By convention, the last agent in the `*` chain is the foreperson. Agents see each other's reasoning; parallel agents do not.

```kotlin
val forum = initiator * analyst * critic * captain
// Forum<Specs, Decision>

val pipeline = inputConverter then forum then formatter
// Pipeline<Input, FormattedDecision>
```

### `.loop {}` — Iterative Execution

The block receives the output and returns the next input to continue, or `null` to stop. Fully composable in pipelines.

```kotlin
// Refine a value until it meets a threshold
val refineLoop = refine.loop { result -> if (result.score >= 90) null else result }

// Loop over a multi-step pipeline
val generateAndEvaluate = generate then evaluate
val qualityLoop = generateAndEvaluate.loop { result ->
    if (result.quality >= 90f) null else result.spec   // null = done, spec = continue
}

// Compose in a larger pipeline
val pipeline = prepare then qualityLoop then publish
val output = pipeline(input)
```

**Quality gate pattern** — run parallel spec generation in a loop until the evaluator is satisfied:

```kotlin
val specPipeline   = (useCases / glossary / actors / features) then gather
val specsEvaluator = agent<SpecsParcel, Float>("eval") { execute { score(it) } }

var specs   = SpecsParcel(description = "build a user API")
var quality = 0f
while (quality < 90f) {
    specs   = specPipeline(specs)
    quality = specsEvaluator(specs)
}
// specs now meets quality threshold
```

The `next` block is plain Kotlin — call other agents, read external state, anything goes:

```kotlin
val loop = body.loop { result ->
    if (validator(result)) null   // validator is just a function call — stop
    else transform(result)        // or produce the next input to continue
}
```

### `.branch {}` — Conditional Routing on Sealed Types

Routes the output of an agent to a different handler per sealed variant. All branches must produce the same `OUT` type. Unhandled variants throw at invocation.

```kotlin
sealed interface ReviewResult
data class Passed(val score: Double)        : ReviewResult
data class Failed(val issues: List<String>) : ReviewResult
data class NeedsRevision(val feedback: String) : ReviewResult

val afterReview = reviewer.branch {
    on<Passed>()        then deployer
    on<Failed>()        then agent<Failed, Report>("fail-report")  { execute { ... } }
    on<NeedsRevision>() then (reviser then reviewer)  // pipeline on a variant
}
// Branch<CodeBundle, Report>
```

Fully composable with `then`:

```kotlin
val pipeline = coder then afterReview then notifier
// Pipeline<Specification, Notification>
```

Agents inside the branch are placement-tracked — they cannot be reused elsewhere.

---

## Single-Placement Rule

Each `agent<>()` call is an instance. An instance can only be placed in one structure, ever — across Pipelines, Parallels, and Forums.

```kotlin
val a = agent<A, B>("a") {}
val b = agent<B, C>("b") {}

a then b  // a is now placed in "pipeline"

a then c  // IllegalArgumentException:
          // Agent "a" is already placed in pipeline.
          // Each agent instance can only participate once.
          // Create a new instance for "pipeline".
```

Cross-structure reuse is also caught:

```kotlin
a then b      // a placed in pipeline
a * forum     // IllegalArgumentException — same instance, different structure
```

---

## Agent Execution

Agents have two execution paths — mutually exclusive, validated at construction.

### Code agents — `execute { }`

Plain Kotlin. No LLM, no skills. Use for deterministic steps: parsing, formatting, validation, transformation.

```kotlin
val tokenizer = agent<RawText, Tokens>("tokenizer") {
    execute { input -> Tokens(input.text.split(" ")) }
}

val formatter = agent<CodeBundle, FormattedCode>("formatter") {
    execute { input -> FormattedCode(ktlint(input.source)) }
}

val gate = agent<SpecsParcel, Float>("quality-gate") {
    execute { specs ->
        (specs.useCases.size + specs.features.size + specs.requirements.size) / 30f * 100f
    }
}

// Invoked directly or as part of a pipeline
val score = gate(specs)           // Agent<SpecsParcel, Float> is callable
val tokens = tokenizer(rawText)   // same call syntax everywhere
```

### LLM agents — `model { }` + `skills { }` *(planned)*

```kotlin
val coder = agent<Specification, CodeBundle>("coder") {
    model { claude("claude-sonnet-4-6"); temperature = 0.1 }
    skills {
        skill<Specification, CodeBundle>("write-code") { ... }
    }
}
```

Both types are `Agent<IN, OUT>` and compose identically in pipelines.

### Running a pipeline

`Pipeline` composes execution functions at construction time — no runtime casts, fully type-safe:

```kotlin
val pipeline = parser then formatter then validator
val result = pipeline(input)  // Pipeline<Input, ValidationResult>
```

---

## Skills

An agent is a container of typed skills. Each skill has its own `<IN, OUT>`. At least one must produce the agent's `OUT` type — validated at construction.

```kotlin
val analyze = skill<TaskRequest, Specification>("analyze") {
    implementedBy { input ->
        Specification(input.text.split(",").map { it.trim() })
    }
}

val coder = agent<TaskRequest, Specification>("spec-master") {
    skills {
        +analyze                                          // pre-defined skill
        skill<String, String>("format") { ... }          // inline utility skill
    }
}

// Call any skill directly — fully typed, no casts
analyze.execute(TaskRequest("getUsers, createUser"))
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
- [x] `execute { }` — code agent execution (plain Kotlin)
- [x] Skill model — `implementedBy`, typed `execute`
- [x] `then` — sequential pipeline with composed execution (no runtime casts)
- [x] `/` — parallel fan-out
- [x] `*` — forum (multi-agent discussion)
- [x] Single-placement enforcement across all structure types
- [x] `.loop {}` — iterative execution with `(OUT) -> IN?` feedback block
- [x] `.branch {}` — conditional routing on sealed types, composable with `then`
- [ ] `model { }` — LLM inference path
- [ ] `>>` — security/education wrap

**Phase 2 — Runtime** *(Q2 2026)*
- [ ] Detekt custom rule — static detection of reused agent instances
- [ ] Forum discussion rounds
- [ ] Parallel coroutine execution
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
