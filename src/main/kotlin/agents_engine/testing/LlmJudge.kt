package agents_engine.testing

import agents_engine.generation.Generable
import agents_engine.generation.Guide
import agents_engine.generation.fromLlmOutput
import agents_engine.generation.toLlmInput
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient

/**
 * `agents_engine/testing/LlmJudge.kt` — opt-in LLM-as-judge scorer
 * (#2494, part of the #2491 eval epic).
 *
 * **Advisory, not gating.** Judge verdicts capture qualitative criteria
 * that resist deterministic assertion — tone, relevance, completeness.
 * They are explicitly separate from the [EvalCase]'s deterministic
 * `expect { }` blocks: a judge low score never fails a case. The
 * verdict surfaces on [EvalResult.judgeVerdicts] for the test report
 * to display alongside the deterministic pass/fail.
 *
 * **Typed rubric + structured verdict.** The judge prompt is typed
 * config ([JudgeRubric]); the verdict is a `@Generable` ([JudgeVerdict])
 * so the judge model returns structured JSON that the framework parses.
 * Free-text judge prompts → free-text verdicts are explicitly avoided.
 *
 * ```kotlin
 * val rubric = JudgeRubric(
 *     criteria = "Tone: professional, calm, neutral; no jargon.",
 *     judgeModel = DeterministicModelClient(
 *         LlmResponse.Text(""${'"'}{"score":7,"rationale":"slightly informal"}""${'"'}),
 *     ),
 * )
 * val case = eval<String, Review>("repo-review") {
 *     input(spec)
 *     expect("approved") { it.approved }
 *     judge("tone", rubric)
 * }
 * val result = case.run(reviewAgent)
 * assertTrue(result.passed) { result.failureMessage }       // gated only by `expect`
 * println(result.judgeVerdicts["tone"])                     // advisory score visible
 * ```
 *
 * **Pinnable model.** The [JudgeRubric.judgeModel] is a regular
 * [ModelClient] — most often a [DeterministicModelClient] in unit
 * tests (so the judge itself is reproducible) or a pinned cloud model
 * in a `live-cloud-api`-tagged eval suite. Either way the judge model
 * is independent of the production agent's model.
 *
 * Pairs with #2491 (eval epic), #2492 (DeterministicModelClient),
 * #2493 (eval DSL).
 */

/**
 * Typed rubric for an LLM-as-judge scoring pass. The framework renders
 * this as a system prompt for [judgeModel] when the judge runs.
 *
 * @property criteria the rubric text shown to the judge model. Be
 *   specific about what's being scored ("tone: professional and
 *   neutral" vs. "good answer").
 * @property scoreRange the integer range judges score within. Defaults
 *   to `0..10`. Verdict scores outside this range trip a clear error
 *   in [LlmJudge.score].
 * @property judgeModel the [ModelClient] that produces the verdict.
 *   Independent of the production agent's model — use a pinned model
 *   here. For reproducible unit tests, use [DeterministicModelClient].
 */
data class JudgeRubric(
    val criteria: String,
    val scoreRange: IntRange = 0..10,
    val judgeModel: ModelClient,
)

/**
 * Structured judge output. `@Generable` so the judge model returns
 * JSON the framework parses through the existing `fromLlmOutput`
 * pipeline — no string parsing in test code.
 *
 * @property score the integer score within [JudgeRubric.scoreRange].
 *   Out-of-range scores throw at parse time.
 * @property rationale one sentence justifying the score. Surfaces in
 *   test reports alongside the deterministic outcomes.
 */
@Generable("A structured verdict from an LLM-as-judge scoring pass.")
data class JudgeVerdict(
    @Guide("Integer score within the rubric's scoreRange.")
    val score: Int,
    @Guide("One sentence justifying the score.")
    val rationale: String,
)

/**
 * Runs a [JudgeRubric] over (input, output) pairs and returns a typed
 * [JudgeVerdict]. Internal — eval cases use the `judge(label, rubric)`
 * DSL on [EvalCaseBuilder] rather than calling this directly.
 */
internal class LlmJudge(private val rubric: JudgeRubric) {
    fun score(input: Any?, output: Any?): JudgeVerdict {
        val messages = listOf(
            LlmMessage(
                role = "system",
                content = """
                    You are a strict but fair judge.

                    Rubric: ${rubric.criteria}

                    Score the assistant's response on an integer scale in ${rubric.scoreRange.first}..${rubric.scoreRange.last}.
                    Respond ONLY with JSON of the shape: {"score": <integer>, "rationale": "<one sentence>"}.
                """.trimIndent(),
            ),
            LlmMessage(
                role = "user",
                content = """
                    Input: ${toLlmInput(input)}
                    Output: ${toLlmInput(output)}
                """.trimIndent(),
            ),
        )
        val response = rubric.judgeModel.chat(messages)
        val text = when (response) {
            is LlmResponse.Text -> response.content
            is LlmResponse.ToolCalls -> error(
                "Judge model returned tool calls instead of a text verdict. " +
                    "The judge model must produce a JSON object matching JudgeVerdict.",
            )
        }
        val verdict = JudgeVerdict::class.fromLlmOutput(text) as? JudgeVerdict
            ?: error(
                "Judge response did not parse as JudgeVerdict. " +
                    "Expected JSON like {\"score\":7,\"rationale\":\"...\"}; got: $text",
            )
        require(verdict.score in rubric.scoreRange) {
            "Judge returned score ${verdict.score} outside rubric range ${rubric.scoreRange}. " +
                "Rationale: ${verdict.rationale}"
        }
        return verdict
    }
}
