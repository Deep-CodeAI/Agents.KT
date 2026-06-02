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
}
