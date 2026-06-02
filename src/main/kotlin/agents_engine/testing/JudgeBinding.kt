package agents_engine.testing

/** Binding of a judge label to its rubric — set via [EvalCaseBuilder.judge]. */
data class JudgeBinding(val label: String, val rubric: JudgeRubric)
