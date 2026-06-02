package agents_engine.testing

import agents_engine.core.Agent

class EvalCase<IN, OUT>(
    val name: String,
    internal val input: IN,
    internal val expectations: List<EvalExpectation<OUT>>,
    /**
     * #2494 — optional LLM judges. Run after the agent succeeds; their
     * verdicts surface on [EvalResult.judgeVerdicts] for the report but
     * NEVER gate [EvalResult.passed]. Empty by default.
     */
    internal val judges: List<JudgeBinding> = emptyList(),
) {
    /**
     * Run this case against [agent], collecting expectation results.
     * Captures exceptions from the agent invocation as a hard failure
     * (the eval can't proceed without the output).
     *
     * After the deterministic expectations resolve, any registered LLM
     * judges (#2494) score the output advisory. Their verdicts attach
     * to [EvalResult.judgeVerdicts] but never affect [EvalResult.passed].
     * A judge that itself throws (model returned garbage, etc.) records
     * a failure detail on the result without gating pass/fail.
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
        // #2494 — advisory judge pass. Errors from the judge itself
        // (parse failures, out-of-range scores) are captured on the
        // verdict map but never gate pass/fail.
        val judgeVerdicts = LinkedHashMap<String, JudgeOutcome>()
        for (binding in judges) {
            val outcome = try {
                val verdict = LlmJudge(binding.rubric).score(input, output)
                JudgeOutcome.Scored(verdict)
            } catch (t: Throwable) {
                JudgeOutcome.Errored(t.message ?: t::class.simpleName ?: "judge threw")
            }
            judgeVerdicts[binding.label] = outcome
        }
        return EvalResult(
            caseName = name,
            output = output,
            outcomes = outcomes,
            invocationError = null,
            judgeVerdicts = judgeVerdicts,
        )
    }
}
