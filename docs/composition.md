[← Back to README](../README.md)

## Composition Operators

### `then` — Sequential Pipeline

```kotlin
val pipeline = specMaster then coder then reviewer
// Pipeline<TaskRequest, ReviewResult>

val full = (specMaster then coder) then (reviewer then deployer)
```

### `/` — Parallel Fan-Out

All agents receive the same input concurrently via coroutines. The next stage receives `List<OUT>`.

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

### `.aggregate {}` — One-Line Ensembles over `/` (#3872)

Built-in typed aggregators collapse a fan-out's `List<OUT>` to one `OUT` — no custom reducer skill needed:

```kotlin
val ensemble = (a / b / c).aggregate { majorityVote() }
// or: selectByMax { it.confidence }
// or: bestOfN { judge(it).score }          // scorer runs once per branch output
// or: weighted(mapOf(expert to 3.0))       // missing agents default to 1.0
// Pipeline<IN, OUT> — composes and streams like any pipeline
```

Sugar over `then`: builds a deterministic reducer agent named `aggregate-<strategy>`, so the aggregation is auditable as that agent's events with the strategy in the name. Ties break deterministically (first-encountered in branch order). If every branch fails, the parallel stage fails before the reducer runs (`Failed` terminal on the session path).

### `firstOf` / `.speculative(n)` — Speculative Execution (#3869)

LLM latency is variance-dominated: race N equivalent branches against the same input and return the **first success** at the winner's latency.

```kotlin
val fast    = firstOf(primary, fallbackProvider)   // distinct agents (single-placement marked)
val sampled = generator.speculative(3)             // same agent, 3 concurrent racers

fast.onRaceSettled { winner, cancelled, ms -> log("$winner won in ${ms}ms, cancelled $cancelled") }
```

Semantics: a failing branch does NOT settle the race; all-fail throws the last failure. Losers are cancelled but not awaited (the sacrificial-worker precedent of blocking tools) — a suspending loser stops promptly, a blocking body may finish in the background with its result discarded. **Budget honesty:** losers' tokens up to cancellation are real provider spend; cap worst-case by bounding N (cross-branch accounting of cancelled partial usage is a known gap on #3869). `firstOf.session(input)` streams every racer's events and completes under the winner's id.

### `*` — Forum (Multi-Agent Coordination)

The `*` shorthand is convention over configuration: every agent receives the same input, all non-final agents run concurrently as participants, and the last agent is the captain. The captain determines the forum `OUT` type and is the default finalizer.

```kotlin
val forum = initiator * analyst * critic * captain
// Forum<Specs, Decision>

val pipeline = inputConverter then forum then formatter
// Pipeline<Input, FormattedDecision>

// Track forum outputs as they arrive
forum.onMentionEmitted { agentName, output ->
    println("[$agentName]: $output")
}
```

For explicit roles and permissions, use the forum DSL:

```kotlin
val forum = forum<Specs, Decision> {
    participant(initiator)
    participant(analyst)
    captain(captain)
    allowForumReturn(analyst)   // optional; captain may return by default
}
```

`forum_return` is a built-in forum capability. The captain gets it automatically; additional registered participants can be granted it with `allowForumReturn(...)`. If nobody calls `forum_return`, the captain's normal return value becomes the forum result.

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

### Built-in Forum Captains (#3877)

Named aggregation strategies for `forum` verdicts — deterministic transcript captains, auditable by name:

```kotlin
forum<Question, Answer> {
    participant(expert1); participant(expert2); participant(expert3)
    transcriptCaptain(consensusCaptain(quorum = 2))            // N identical verdicts or fail loud with the tally
    // transcriptCaptain(weightedCaptain(mapOf("expert1" to 3.0)))  // weighted vote by panelist name
    // transcriptCaptain(byzantineCaptain())                        // median of numeric verdicts — robust to outliers
}
```

`byzantineCaptain` is the 1-dimensional geometric median (robust to ⌈n/2⌉−1 adversaries); vector Krum / Weiszfeld for embedding outputs is a tracked follow-up.

### `.loopUntil { }` + `evalGate` — Reflexion / Evaluator-Optimizer (#3870)

The named exit-condition shape over `.loop {}`:

```kotlin
val gate = evalGate(qualityRubric, threshold = 7)     // LLM-as-judge pass/fail gate
val refiner = drafter.loopUntil(maxIterations = 5) { draft -> gate.pass(draft) }

val poller = checker.loopUntil(
    maxIterations = 10,
    feedback = { it.retryRequest },                   // OUT -> next IN; omit when IN == OUT
) { it.status == Done }
```

`evalGate(rubric, threshold)` runs one judge-model scoring pass per call (`gate.lastVerdict` keeps the rationale) — use a cheap pinned judge model, or `DeterministicModelClient` in tests. `maxIterations` still throws on overrun: a predicate that never fires is a bug, not an infinite loop.

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

### `handoff` — Named Transfer to Specialists (#3871)

`branch` semantics with an audit contract: a triage/router agent transfers control to a specialist based on its typed output, and the transfer is observable as a first-class event.

```kotlin
val flow = triage handoff {
    on<BillingTask>() then billing
    on<TechTask>()    then tech
}
// Branch<UserMessage, Resolution>

triage.onHandoff { toAgent, inputType -> log("handoff -> $toAgent ($inputType)") }
// or via observe { }: PipelineEvent.HandoffPerformed(toAgent, decisionInputType)
```

**Unlike OpenAI-Swarm-style handoff, the target never shares or mutates the source's conversation history.** Each specialist receives only its declared input type — the typed boundary *is* the context contract, and the single-placement rule holds across the transfer. Sealed source outputs get the same construction-time exhaustiveness validation as `branch`. The audit signal fires on both the blocking and streaming (`session`) paths.

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
A * B          : Agent<X,Y> * Agent<X,Z>       → Forum<X,Z>
A.loop { }     : (Pipeline<X,Y> | Agent<X,Y>)  → Loop<X,Y>   (null = stop, X = continue)
A.branch { }   : Agent<X, Sealed<Y>)           → Branch<X,Z>  (all variants → same Z)
```

---
