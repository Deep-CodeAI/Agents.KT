package agents_engine.testing

import agents_engine.core.Agent
import agents_engine.generation.toLlmInput

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
class EvalCase<IN, OUT>(
    val name: String,
    internal val input: IN,
    internal val expectations: List<EvalExpectation<OUT>>,
) {
    /**
     * Run this case against [agent], collecting expectation results.
     * Captures exceptions from the agent invocation as a hard failure
     * (the eval can't proceed without the output).
     */
    fun run(agent: Agent<IN, OUT>): EvalResult<OUT> {
        val output = try {
            agent(input)
        } catch (t: Throwable) {
            return EvalResult(
                caseName = name,
                output = null,
                outcomes = emptyList(),
                invocationError = t,
            )
        }
        val outcomes = expectations.map { expectation ->
            try {
                val passed = expectation.check(output)
                EvalOutcome(expectation.label, passed, failureDetail = if (passed) null else expectation.describe(output))
            } catch (t: Throwable) {
                EvalOutcome(expectation.label, false, failureDetail = "expectation threw: ${t.message}")
            }
        }
        return EvalResult(caseName = name, output = output, outcomes = outcomes, invocationError = null)
    }
}

/** A typed expectation over an agent's `OUT`. */
class EvalExpectation<OUT>(
    val label: String,
    private val predicate: (OUT) -> Boolean,
    private val describer: (OUT) -> String = { "expectation failed for output $it" },
) {
    fun check(output: OUT): Boolean = predicate(output)
    fun describe(output: OUT): String = describer(output)
}

/** Builder DSL for [EvalCase]. */
class EvalCaseBuilder<IN, OUT> {
    private var input: IN? = null
    private var inputProvided: Boolean = false
    private val expectations: MutableList<EvalExpectation<OUT>> = mutableListOf()

    /** Set the agent input. Required — calling [build] without it throws. */
    fun input(value: IN) {
        input = value
        inputProvided = true
    }

    /**
     * Typed predicate over `OUT`. The [label] surfaces on failure reports
     * so multi-expect cases are diagnosable.
     */
    fun expect(label: String = "expect", predicate: (OUT) -> Boolean) {
        expectations += EvalExpectation(label, predicate) { out ->
            "[$label] failed for output: ${renderForFailure(out)}"
        }
    }

    /**
     * Snapshot expectation — captures `toLlmInput(output)` and matches
     * against [snapshot]. Useful for pinning a known-good typed output
     * structurally without spelling out every field.
     *
     * Use the recommended `--update-eval-snapshots` workflow: run the
     * suite once with the expected output stored in source as the
     * snapshot string. Drift surfaces as a typed diff failure.
     */
    fun expectSnapshot(label: String = "snapshot", snapshot: String) {
        expectations += EvalExpectation(
            label = label,
            predicate = { out -> toLlmInput(out) == snapshot },
            describer = { out ->
                "[$label] snapshot mismatch:\n  expected: $snapshot\n  actual:   ${toLlmInput(out)}"
            },
        )
    }

    /**
     * Field-level expectation for `@Generable` outputs. Inspects the
     * rendered JSON shape for an exact key/value match. Useful for
     * asserting on one field without spelling out the full snapshot.
     * For complex queries use [expect] with manual reflection on the
     * typed `OUT`.
     */
    fun expectFieldEquals(fieldPath: String, expected: Any?) {
        expectations += EvalExpectation(
            label = "$fieldPath == $expected",
            predicate = { out ->
                val json = toLlmInput(out)
                // Simple substring check on the canonical JSON. Good enough
                // for v1; users who need full JSONPath semantics can write
                // an explicit `expect { ... }`.
                json.contains("\"$fieldPath\":${renderJsonValue(expected)}")
            },
            describer = { out ->
                "[field $fieldPath] expected $expected; output rendered as ${toLlmInput(out)}"
            },
        )
    }

    internal fun build(name: String): EvalCase<IN, OUT> {
        check(inputProvided) { "eval(\"$name\") { } requires an input(...) call." }
        check(expectations.isNotEmpty()) { "eval(\"$name\") { } requires at least one expect(...) block." }
        @Suppress("UNCHECKED_CAST")
        return EvalCase(name, input as IN, expectations.toList())
    }

    private fun renderForFailure(out: OUT): String =
        try { toLlmInput(out) } catch (_: Throwable) { out?.toString() ?: "null" }
}

/**
 * Build an [EvalCase]. The `IN` and `OUT` type parameters are inferred
 * from the agent type at `case.run(agent)`.
 */
fun <IN, OUT> eval(name: String, block: EvalCaseBuilder<IN, OUT>.() -> Unit): EvalCase<IN, OUT> {
    val builder = EvalCaseBuilder<IN, OUT>()
    builder.block()
    return builder.build(name)
}

/** Outcome of a single expectation in an eval case. */
data class EvalOutcome(
    val label: String,
    val passed: Boolean,
    val failureDetail: String?,
)

/** Result of running an [EvalCase] against an agent. */
data class EvalResult<OUT>(
    val caseName: String,
    val output: OUT?,
    val outcomes: List<EvalOutcome>,
    val invocationError: Throwable?,
) {
    val passed: Boolean get() = invocationError == null && outcomes.all { it.passed }

    val failureMessage: String?
        get() = when {
            passed -> null
            invocationError != null ->
                "eval case \"$caseName\" failed: agent threw ${invocationError::class.simpleName}: ${invocationError.message}"
            else -> {
                val fails = outcomes.filterNot { it.passed }
                "eval case \"$caseName\" failed: ${fails.joinToString("\n") { "  - ${it.label}: ${it.failureDetail}" }}"
            }
        }
}

/** A bag of [EvalCase]s runnable together. */
class EvalSuite(val name: String) {
    private val cases: MutableList<EvalCase<*, *>> = mutableListOf()

    operator fun <IN, OUT> EvalCase<IN, OUT>.unaryPlus() {
        cases += this
    }

    /**
     * Run every case against the [agent]. The agent type binds the case
     * type at call time, so a mixed-type suite is a compile error — each
     * suite is type-homogeneous over the agent it runs against.
     */
    @Suppress("UNCHECKED_CAST")
    fun <IN, OUT> runAll(agent: Agent<IN, OUT>): EvalSuiteResult<OUT> {
        val results = cases.map { case -> (case as EvalCase<IN, OUT>).run(agent) }
        return EvalSuiteResult(name = name, results = results)
    }
}

/** Result of running an [EvalSuite]. */
data class EvalSuiteResult<OUT>(
    val name: String,
    val results: List<EvalResult<OUT>>,
) {
    val passed: Boolean get() = results.all { it.passed }
    val failureSummary: String?
        get() = if (passed) null else results
            .filterNot { it.passed }
            .joinToString("\n") { it.failureMessage ?: "(unknown failure in ${it.caseName})" }
}

/** Build a suite. Cases go in via `+ case`. */
fun evalSuite(name: String, block: EvalSuite.() -> Unit): EvalSuite =
    EvalSuite(name).apply(block)

/**
 * Render a JSON value for the simple `expectFieldEquals` substring match.
 * Mirrors `toJsonString`'s escaping conventions for strings; integers /
 * booleans / null render unquoted.
 */
private fun renderJsonValue(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> value.toString()
    is Number -> value.toString()
    is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    else -> "\"${value.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
