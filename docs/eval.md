[← Back to README](../README.md)

# Eval harness

Two pieces ship today, layered:

- **`DeterministicModelClient`** (#2492) — a `ModelClient` that scripts responses, no network. Pairs with any agent so you can run the full agentic loop deterministically.
- **`eval { }` DSL** (#2493) — declarative cases with typed assertions over the agent's `OUT`. Supports per-field checks, full structural snapshots, and grouped suites.

Both live in package `agents_engine.testing` and ship in the main module — usable from any consumer's test source set without an extra artifact.

---

## `DeterministicModelClient`

Hand back a pre-scripted sequence of `LlmResponse`s, one per `chat` call. The agent's loop runs end-to-end against the script, with the same Started → ArgsDelta → Finished → End chunk sequence on the streaming side (the default `ModelClient.chatStream` wraps `chat`).

```kotlin
import agents_engine.testing.DeterministicModelClient
import agents_engine.model.LlmResponse
import agents_engine.model.ToolCall

val mock = DeterministicModelClient(
    LlmResponse.ToolCalls(listOf(ToolCall("lookup", mapOf("id" to "42")))),
    LlmResponse.Text("found 42"),
)
val agent = agent<String, String>("test") {
    model { ollama("t"); client = mock }
    tools { tool("lookup", "lookup") { args -> "value-${args["id"]}" } }
    skills { skill<String, String>("s", "") { tools("lookup") } }
}

agent("what is 42?")    // → "found 42"
mock.remaining()        // → 0 (both scripted responses consumed)
mock.requests           // List<List<LlmMessage>> — every `chat` call's input
```

### What you get

- **Byte-determinism.** Two runs against the same script + same agent + same input produce identical output.
- **Request history.** `mock.requests` records every message list the agent built up across turns. Useful for asserting on conversation shape.
- **Clear exhaustion errors.** If the agent calls `chat` more times than there are scripted responses, the client throws `DeterministicScriptExhausted(callIndex, scriptSize, lastMessages)` naming the offending turn.

### Out of scope (v1)

- **Record-from-live.** The #2492 ticket mentions "record-once/replay-many." That needs an HTTP-fixture story we'll write when there's demand. For now: hand-script the responses or compose with a recording-decorator pattern in your own test code.
- **Per-token streaming chunks.** `chatStream` uses the default chunk-from-chat wrap — good enough for asserting on the streaming `AgentEvent` shape, not useful for testing provider-specific mid-stream edge cases.

---

## `eval { }` DSL

Declarative cases with typed predicates over the agent's `OUT`.

```kotlin
import agents_engine.testing.eval

val case = eval<String, Review>("repo-review") {
    input(SpecText("review this repository"))
    expect("nonempty risks") { it.risks.isNotEmpty() }
    expect("at least 3 risks") { it.risks.size >= 3 }
}

val result = case.run(reviewAgent)
assertTrue(result.passed) { result.failureMessage }
```

### Three expectation styles

```kotlin
// 1. Typed predicate — runs against the parsed OUT, not a string.
expect("approved") { it.approved == true }

// 2. Snapshot — pins the canonical toLlmInput(output) JSON.
expectSnapshot(snapshot = """{"text":"Hello","approved":true}""")

// 3. Single-field substring on the rendered JSON — quick for one field.
expectFieldEquals("approved", true)
```

All three compose: multiple `expect` blocks must all pass for the case to pass. The failure message names every failing label and renders the typed output for diagnosis.

### Suite mode

Group cases:

```kotlin
import agents_engine.testing.evalSuite

class GreetingEvalTest {
    @Test
    fun `greeting suite`() {
        val suite = evalSuite("greeting") {
            + eval<String, String>("nonempty") {
                input("hi")
                expect("nonempty") { it.isNotEmpty() }
            }
            + eval<String, String>("polite") {
                input("hi")
                expect("contains hello") { "hello" in it.lowercase() }
            }
        }
        val result = suite.runAll(greetingAgent)
        assertTrue(result.passed) { result.failureSummary }
    }
}
```

Suites are **type-homogeneous over the agent type at call time** — `EvalSuite.runAll<IN, OUT>(agent: Agent<IN, OUT>)` binds the case types at the call site. A mixed-shape suite is a compile error.

### Failure shape

`EvalResult.failureMessage` is `null` on pass, structured on fail:

```
eval case "multi-fail" failed:
  - starts with goodbye: [starts with goodbye] failed for output: "hello world"
```

When the agent throws during invocation, the result carries `invocationError` and the message names the exception. Use as `assertTrue(result.passed) { result.failureMessage }` in JUnit / kotlin-test.

---

## Composition: deterministic eval end-to-end

```kotlin
class RepoReviewEvalTest {
    @Test
    fun `repo review hits the audit criteria`() {
        val mock = DeterministicModelClient(
            LlmResponse.Text("""{"text":"All good","approved":true,"risks":[]}"""),
        )
        val agent = agent<String, Review>("review") {
            model { ollama("test"); client = mock }
            skills { skill<String, Review>("review", "") { tools() } }
        }
        val case = eval<String, Review>("approved-no-risks") {
            input("review the repo")
            expect("approved") { it.approved }
            expect("no risks") { it.risks.isEmpty() }
        }
        val result = case.run(agent)
        assertTrue(result.passed, result.failureMessage)
    }
}
```

The combination of `DeterministicModelClient` + `eval { }` gives you:

- No network, no live LLM, no nondeterminism.
- Typed assertions against the agent's `OUT` (not regex on the wire).
- Pinning the model's response in source — when the prompt or schema changes, you update the script *and* the snapshot in the same diff.

For real-model regression coverage there's the existing `live-llm` / `live-cloud-api` tagged tests; those are nondeterministic by design and out of scope for the eval harness.

---

## Related docs

- [`docs/testing.md`](testing.md) — existing testing conventions (task names, integration test setup, mutation testing).
- [`docs/observability.md`](observability.md) — the bridges that consume `AgentEvent` and `PipelineEvent` — useful when you're asserting on the streaming flow during eval.

Sources: `agents_engine/testing/DeterministicModelClient.kt`, `agents_engine/testing/EvalDsl.kt`.

Tests: `DeterministicModelClientTest.kt`, `EvalDslTest.kt`.
