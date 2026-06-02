package agents_engine.testing

/** A typed expectation over an agent's `OUT`. */
class EvalExpectation<OUT>(
    val label: String,
    private val predicate: (OUT) -> Boolean,
    private val describer: (OUT) -> String = { "expectation failed for output $it" },
) {
    fun check(output: OUT): Boolean = predicate(output)
    fun describe(output: OUT): String = describer(output)
}
