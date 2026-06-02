package agents_engine.testing

import agents_engine.model.ModelClient

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
