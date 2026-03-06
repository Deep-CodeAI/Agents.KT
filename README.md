# Agents.KT

**Typed Kotlin DSL framework for AI agent systems.**

*Define Freely. Compose Strictly. Ship Reliably.*

---

Every agent is `Agent<IN, OUT>`. One input type, one output type, one job. Type mismatches and wrong compositions are caught by the compiler. Reused agent instances are caught at construction time — a Detekt rule or compiler plugin for static detection is on the roadmap.

```kotlin
val specMaster = agent<TaskRequest, Specification>("spec-master") {
    skills {
        skill<TaskRequest, Specification>("create-spec") {
            implementedBy { input -> Specification(parse(input.text)) }
        }
    }
}

val coder    = agent<Specification, CodeBundle>("coder") { ... }
val reviewer = agent<CodeBundle, ReviewResult>("reviewer") { ... }

// Compiler checks every boundary
val pipeline = specMaster then coder then reviewer
// Pipeline<TaskRequest, ReviewResult>
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

All agents receive the same input independently. The next stage receives `List<OUT>`.

```kotlin
val parallel = reviewerA / reviewerB / reviewerC
// Parallel<CodeBundle, Review>

val pipeline = coder then parallel then synthesizer
// synthesizer: Agent<List<Review>, FinalResult>
```

**Liskov:** declare agents as the common supertype — implementations may return subtypes.

```kotlin
sealed interface Review
data class QuickReview(val summary: String) : Review
data class DeepReview(val issues: List<String>, val score: Double) : Review

val quick = agent<CodeBundle, Review>("quick") { ... }  // returns QuickReview
val deep  = agent<CodeBundle, Review>("deep")  { ... }  // returns DeepReview

val parallel = quick / deep  // Parallel<CodeBundle, Review>
```

### `*` — Forum (Multi-Agent Discussion)

Think *jury deliberation* — the case lands on the table, jurors discuss across rounds, one agent delivers the verdict. By convention, the last agent in the `*` chain is the foreperson. Agents see each other's reasoning; parallel agents do not.

```kotlin
val forum = initiator * analyst * critic * captain
// Forum<Specs, Decision>

val pipeline = inputConverter then forum then formatter
// Pipeline<Input, FormattedDecision>
```

### `.branch {}` — Conditional Routing on Sealed Types *(planned)*

```kotlin
val afterReview = reviewer.branch {
    on<ReviewResult.Passed>()        then deployer
    on<ReviewResult.Failed>()        then coder then reviewer  // retry
    on<ReviewResult.NeedsRevision>() then coder then reviewer  // fix
}
// Compiler forces exhaustive handling of all sealed variants
```

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
Agent<A, B>   : A → B
A then B      : Agent<X,Y> then Agent<Y,Z>     → Pipeline<X,Z>
A / B         : Agent<X,Y> / Agent<X,Y>        → Parallel<X,Y>  →  List<Y> to next
A * B         : Agent<X,Y> * Agent<*,Z>        → Forum<X,Z>
A.branch {}   : Agent<X, Sealed<Y>>            → Branch<X,Z>
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
- [x] Skill model — `implementedBy`, typed `execute`
- [x] `then` — sequential pipeline
- [x] `/` — parallel fan-out
- [x] `*` — forum (multi-agent discussion)
- [x] Single-placement enforcement across all structure types
- [ ] `.branch {}` — conditional routing on sealed types
- [ ] `>>` — security/education wrap

**Phase 2 — Runtime** *(Q2 2026)*
- [ ] Detekt custom rule — static detection of reused agent instances
- [ ] Execution engine — `pipeline.execute(input)`
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
