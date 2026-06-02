package agents_engine.testing

import agents_engine.generation.toLlmInput

/** Builder DSL for [EvalCase]. */
class EvalCaseBuilder<IN, OUT> {
    private var input: IN? = null
    private var inputProvided: Boolean = false
    private val expectations: MutableList<EvalExpectation<OUT>> = mutableListOf()
    private val judges: MutableList<JudgeBinding> = mutableListOf()

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

    /**
     * #2494 — register an advisory LLM-as-judge scorer. The judge runs
     * AFTER the agent succeeds and produces a typed [JudgeVerdict]. The
     * verdict surfaces on [EvalResult.judgeVerdicts] for the test
     * report but does NOT gate the case's pass/fail — judges are
     * advisory by design, distinct from the deterministic `expect`
     * blocks. Multiple judges per case are allowed; each is keyed by
     * its [label] in the result map.
     */
    fun judge(label: String, rubric: JudgeRubric) {
        require(label.isNotBlank()) { "judge label must not be blank" }
        require(judges.none { it.label == label }) { "duplicate judge label: $label" }
        judges += JudgeBinding(label, rubric)
    }

    internal fun build(name: String): EvalCase<IN, OUT> {
        check(inputProvided) { "eval(\"$name\") { } requires an input(...) call." }
        check(expectations.isNotEmpty()) { "eval(\"$name\") { } requires at least one expect(...) block." }
        @Suppress("UNCHECKED_CAST")
        return EvalCase(name, input as IN, expectations.toList(), judges.toList())
    }

    private fun renderForFailure(out: OUT): String =
        try { toLlmInput(out) } catch (_: Throwable) { out?.toString() ?: "null" }
}

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
