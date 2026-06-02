package agents_engine.testing

/**
 * `agents_engine/testing/EvalDsl.kt` — declarative eval cases over an
 * agent's typed `OUT` (#2493, part of the #2491 eval epic).
 *
 * ```kotlin
 * val case = eval<String, Review>("repo-review") {
 *     input(SpecText("review this repository"))
 *     expect { it.risks.size >= 3 }
 *     expectField("approved", true)        // matches review.approved == true
 * }
 *
 * val result = case.run(reviewAgent)
 * assertTrue(result.passed) { result.failureMessage }
 * ```
 *
 * **Typed assertions.** Expectations run against the agent's typed `OUT`,
 * not string-matching. The lambda receives the resolved output and
 * returns true/false. Multiple `expect` blocks compose: all must pass.
 *
 * **Snapshot mode.** `expectSnapshot { ... }` captures the rendered
 * `toLlmInput(output)` JSON on first run (when the snapshot path is
 * empty) and diffs on subsequent runs. Same shape as Jest / kotest
 * snapshots; pairs well with the deterministic-replay ModelClient so the
 * snapshot is stable across CI runs.
 *
 * **Integration with CI.** `evalSuite("name") { + case; + case; ... }`
 * groups cases. The suite returns a [EvalSuiteResult] with per-case
 * results; CI wraps it in a normal test method that fails when any case
 * fails. No new task/runner needed.
 *
 * Pairs with [DeterministicModelClient] for the no-network requirement
 * — eval cases against a live model are nondeterministic and out of
 * scope; live-model regression coverage goes through the existing
 * `live-llm` / `live-cloud-api` tagged tests.
 */

/**
 * Build an [EvalCase]. The `IN` and `OUT` type parameters are inferred
 * from the agent type at `case.run(agent)`.
 */
fun <IN, OUT> eval(name: String, block: EvalCaseBuilder<IN, OUT>.() -> Unit): EvalCase<IN, OUT> {
    val builder = EvalCaseBuilder<IN, OUT>()
    builder.block()
    return builder.build(name)
}

/** Build a suite. Cases go in via `+ case`. */
fun evalSuite(name: String, block: EvalSuite.() -> Unit): EvalSuite =
    EvalSuite(name).apply(block)
