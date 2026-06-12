# Patterns — Anthropic's "Building Effective Agents", 1:1 in Agents.KT (#3878)

Anthropic's [Building Effective Agents](https://www.anthropic.com/research/building-effective-agents) catalog maps **one-to-one** onto Agents.KT primitives — no new abstractions, just the operators the framework already ships. Each recipe below is the canonical shape; every operator keeps the typed boundaries, the single-placement rule, streaming sessions, and audit events.

| Anthropic pattern | Agents.KT primitive | Recipe |
|---|---|---|
| Augmented LLM (ReAct) | one agent + `tools(...)` allowlist | [§1](#1-augmented-llm--react) |
| Prompt chaining | `then` | [§2](#2-prompt-chaining) |
| Routing | `handoff` / `branch` | [§3](#3-routing) |
| Parallelization — sectioning | `/` + `.aggregate { }` | [§4](#4-parallelization) |
| Parallelization — voting | `/` + `majorityVote()` / `weighted(...)` | [§4](#4-parallelization) |
| Orchestrator-workers | `forum { }` + captain | [§5](#5-orchestrator-workers) |
| Evaluator-optimizer | `.loopUntil { }` + `evalGate` | [§6](#6-evaluator-optimizer--reflexion) |
| Reflexion | same loop, self-critique stage | [§6](#6-evaluator-optimizer--reflexion) |
| Multi-agent debate | `forum` + `consensusCaptain` | [§7](#7-multi-agent-debate) |
| Speculative sampling (latency) | `firstOf` / `.speculative(n)` | [§8](#8-speculative-execution) |
| Human-in-the-loop | `humanApproval` / `HumanGateRegistry` | [§9](#9-human-in-the-loop) |
| Retrieval (RAG) | `knowledge(key, desc, ragRetriever(...))` | [§10](#10-retrieval-rag) |

## 1. Augmented LLM / ReAct

One agent, a model, and a least-privilege tool allowlist — the runtime refuses anything outside it:

```kotlin
val researcher = agent<String, String>("researcher") {
    model { claude("claude-opus-4-7"); apiKey = key }
    tools { +perplexitySearchTool(perplexityKey) }       // untrusted-output, cited
    skills { skill<String, String>("research", "Research with citations") { tools("perplexitySearch") } }
    budget { maxTurns = 10; maxToolCalls = 16 }
}
```

## 2. Prompt chaining

Sequential stages with compiler-checked boundaries; every stage streams through the parent session (#3866):

```kotlin
val pipeline = parse then generate then review     // Pipeline<RawText, ReviewResult>
pipeline.session(input).events                      // all three stages' events, by agentId
```

## 3. Routing

`handoff` is `branch` plus the audit contract — the transfer fires `onHandoff` / `HandoffPerformed`, and the specialist receives **only its declared input type**, never the router's history (#3871):

```kotlin
val flow = triage handoff {
    on<BillingTask>() then billing
    on<TechTask>()    then tech
}
```

## 4. Parallelization

Sectioning and voting are `/` plus a one-line aggregator (#3872):

```kotlin
val sections = (summarizer / riskScanner / toneChecker)           // List<Finding>
val vote     = (a / b / c).aggregate { majorityVote() }           // first-encountered tie-break
val expertly = (expert / intern1 / intern2).aggregate { weighted(mapOf(expert to 3.0)) }
val best     = (a / b / c).aggregate { bestOfN { judge(it).score } }
```

## 5. Orchestrator-workers

`forum` runs the workers concurrently; the captain synthesizes. Use `transcriptCaptain` when the orchestrator needs to see what every worker said:

```kotlin
val panel = forum<Brief, Plan> {
    participant(architect); participant(securityReviewer); participant(estimator)
    transcriptCaptain(synthesizer)        // Agent<ForumTranscript<Brief>, Plan>
}
```

## 6. Evaluator-optimizer / Reflexion

The named loop + judge gate (#3870). Reflexion is the same loop with the critique folded into the drafting stage:

```kotlin
val gate = evalGate(qualityRubric, threshold = 7)
val refiner = drafter.loopUntil(maxIterations = 5) { draft -> gate.pass(draft) }
// reflexion: drafter = (draft then selfCritique then revise) as the looped pipeline
```

## 7. Multi-agent debate

Members argue concurrently; the verdict needs real agreement — `consensusCaptain` fails loud with the tally rather than silently picking a side (#3877):

```kotlin
val debate = forum<Claim, Verdict> {
    participant(advocate); participant(skeptic); participant(empiricist)
    transcriptCaptain(consensusCaptain(quorum = 2))
    // adversarial-robust numeric variant: transcriptCaptain(byzantineCaptain())
}
```

## 8. Speculative execution

LLM latency is variance-dominated — race equivalents and keep the first success (#3869):

```kotlin
val fast    = firstOf(primary, fallbackProvider)
val sampled = generator.speculative(3)
fast.onRaceSettled { winner, cancelled, ms -> audit(winner, cancelled, ms) }
```

Budget honesty: losers' tokens up to cancellation are real spend — bound N.

## 9. Human-in-the-loop

Tool-level pause with typed resume (#2489), or the named registry surface (#3868):

```kotlin
tools { tool("charge_card", "Charges") { args -> humanApproval { title = "Charge ${args["amount"]}?" } } }

when (val run = gates.guard(checkout, order)) {
    is GateOutcome.Completed -> run.output
    is GateOutcome.Paused -> notifyReviewer(run.gate.gateId, run.gate.reason)
}
// later: gates.find(gateId)?.approve(reviewer = "alice@acme.com")
```

## 10. Retrieval (RAG)

Query-aware knowledge over any `EmbeddingStore` (#3863); retrieval rides the tool path, so it lands in the audit trail with provenance:

```kotlin
skill<String, String>("answer-from-docs", "Answers from project docs") {
    knowledge("project-docs", "Specs and ADRs", ragRetriever(pgvectorStore, embedder) { topK = 8; minScore = 0.55f })
    tools()
}
```

---

Every recipe composes with the rest of the runtime: permission manifests capture the full shape (`agent.permissionManifest()` — now with `@Generable` schemas, #3875), `manifestHash` correlates runtime events back to the reviewed capability graph, and cross-model regression (`suite.runAcrossModels(...)`, #3876) keeps the behavior pinned across providers.
