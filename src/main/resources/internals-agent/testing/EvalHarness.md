---
description: Source-file knowledge for the eval harness in agents_engine/testing/ — #2491 epic (#2492 + #2493 + #2494). Three cooperating pieces in one package. DeterministicModelClient (#2492): scripts LlmResponses in order, fails fast on exhaustion, records requests for assertions, byte-deterministic, default chatStream wrap. eval<IN,OUT>(name) { input + expect + expectSnapshot + expectFieldEquals + judge } DSL (#2493 + #2494): typed predicates, snapshot diff, single-field check; judge(label, rubric) adds an opt-in advisory LLM scorer that does NOT gate pass/fail. EvalResult carries outcomes + invocationError + judgeVerdicts: Map<String, JudgeOutcome> with sealed Scored/Errored variants. evalSuite(name) { + case } bundles cases. JudgeRubric is typed config + a pinnable ModelClient; JudgeVerdict is @Generable so the judge model returns structured JSON. Pass/fail gating is structurally restricted to deterministic expect blocks — judges never gate. Out of scope v1: record-from-live HTTP capture, per-token chunk replay. Call when reasoning about deterministic test patterns, typed-assertion eval cases, or advisory LLM scoring.
---

# `agents_engine/testing/*` — eval harness

Three cooperating pieces in package `agents_engine.testing`:

## `DeterministicModelClient`

```kotlin
class DeterministicModelClient(scripted: List<LlmResponse>) : ModelClient {
    constructor(vararg responses: LlmResponse)
    val requests: List<List<LlmMessage>>    // every chat() call's input
    fun remaining(): Int                    // unconsumed responses
    override fun chat(messages: List<LlmMessage>): LlmResponse
}

class DeterministicScriptExhausted(val callIndex: Int, val scriptSize: Int, val lastMessages: List<LlmMessage>)
    : IllegalStateException(...)
```

Scripts LlmResponses in order, one per chat() call. Streaming uses the default `ModelClient.chatStream` wrap — single-flow Started → ArgsDelta → Finished → End for tool-call responses, TextDelta + End for text responses. Thread-safety: undefined under concurrent use (production loops are single-flight per session).

## `eval { }` DSL

```kotlin
fun <IN, OUT> eval(name: String, block: EvalCaseBuilder<IN, OUT>.() -> Unit): EvalCase<IN, OUT>

class EvalCaseBuilder<IN, OUT> {
    fun input(value: IN)
    fun expect(label: String = "expect", predicate: (OUT) -> Boolean)
    fun expectSnapshot(label: String = "snapshot", snapshot: String)
    fun expectFieldEquals(fieldPath: String, expected: Any?)
    fun judge(label: String, rubric: JudgeRubric)    // #2494 — advisory only
}

class EvalCase<IN, OUT> {
    fun run(agent: Agent<IN, OUT>): EvalResult<OUT>
}

data class EvalResult<OUT>(val caseName, val output, val outcomes, val invocationError,
                            val judgeVerdicts: Map<String, JudgeOutcome>) {
    val passed: Boolean              // gated ONLY by outcomes + invocationError; judges excluded
    val failureMessage: String?
    val judgeSummary: String         // multi-line "[advisory] label: score — rationale"
}

sealed interface JudgeOutcome {
    data class Scored(val verdict: JudgeVerdict)
    data class Errored(val errorDetail: String)
}

data class JudgeRubric(val criteria: String, val scoreRange: IntRange = 0..10, val judgeModel: ModelClient)

@Generable data class JudgeVerdict(val score: Int, val rationale: String)

fun evalSuite(name: String, block: EvalSuite.() -> Unit): EvalSuite

class EvalSuite {
    operator fun <IN, OUT> EvalCase<IN, OUT>.unaryPlus()
    fun <IN, OUT> runAll(agent: Agent<IN, OUT>): EvalSuiteResult<OUT>
}
```

## Composition

`DeterministicModelClient` + `eval { }` ⇒ no-network reproducible eval. The model returns scripted responses; the eval case runs typed predicates on the agent's parsed `OUT`. Both run inside JUnit / kotlin-test alongside the normal suite; no new task or runner needed.

## Three expectation styles

| API | Use when |
|---|---|
| `expect("label") { predicate }` | Typed access to the parsed `OUT`. Most general; reflection-free. |
| `expectSnapshot(snapshot = "...")` | Pin a full `toLlmInput(output)` JSON — diff on regression. |
| `expectFieldEquals(field, value)` | Quick check on one field's rendered JSON value, no full snapshot. |

All compose — multiple expects in one case must all pass. Failure messages name each failing label and render the typed output.

## Failure modes

- Agent threw mid-invocation: `EvalResult.invocationError` is non-null; `outcomes` is empty; `judgeVerdicts` empty (no judges run without an output). `failureMessage` names the exception class + message + case name.
- Expectation predicate returned false: per-outcome entry with `failureDetail` set.
- Predicate itself threw: per-outcome entry with `failureDetail = "expectation threw: ..."`.
- Judge model returned non-JSON or out-of-range score: `JudgeOutcome.Errored(errorDetail)` in `judgeVerdicts[label]`. Does NOT gate `passed`. The judge model is responsible for the structured JSON shape — typically a `DeterministicModelClient` in unit tests or a pinned cloud model in live eval.

## Out of scope (v1)

- **Record-from-live** capture (#2492 ticket mentions it; needs HTTP-fixture infra).
- **Per-token chunk replay** (current streaming uses default ChatChunk wrap).
- **JSONPath in `expectFieldEquals`** (substring match on canonical JSON — good enough for typical fields; complex queries go through `expect { }` with reflection on the typed `OUT`).

## Related files

- `agents_engine/core/Agent.kt` — the agent that consumes the mock + receives the eval input.
- `agents_engine/model/ModelClient.kt` — the SAM interface DeterministicModelClient implements.
- `agents_engine/generation/GenerableSupport.kt` — `toLlmInput` used by snapshot + field expectations to render the typed `OUT`.
