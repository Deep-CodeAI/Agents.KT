package agents_engine.testing

import agents_engine.core.Agent

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

    /**
     * #3876 — cross-model regression: run every case against each labeled
     * agent (one per model/provider) and report behavioral divergence —
     * cases that pass on some models and fail on others. Use distinct
     * agent instances per label (agents are single-placement); pair with
     * live-tagged tests for real providers or [DeterministicModelClient]
     * stubs for hermetic CI.
     */
    fun <IN, OUT> runAcrossModels(vararg agents: Pair<String, Agent<IN, OUT>>): CrossModelEvalResult<OUT> {
        require(agents.isNotEmpty()) { "runAcrossModels needs at least one labeled agent." }
        require(agents.map { it.first }.distinct().size == agents.size) {
            "Model labels must be unique: ${agents.map { it.first }}"
        }
        val byModel = linkedMapOf<String, EvalSuiteResult<OUT>>()
        agents.forEach { (label, agent) -> byModel[label] = runAll(agent) }
        return CrossModelEvalResult(suiteName = name, byModel = byModel)
    }
}
