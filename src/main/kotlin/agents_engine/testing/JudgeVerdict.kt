package agents_engine.testing

import agents_engine.generation.Generable
import agents_engine.generation.Guide

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
