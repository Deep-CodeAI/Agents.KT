package agents_engine.composition.branch

import agents_engine.core.*
import kotlin.reflect.KClass

class Branch<IN, OUT>(
    private val source: Agent<IN, *>,
    private val routes: Map<KClass<*>, (Any?) -> OUT>,
) {
    operator fun invoke(input: IN): OUT {
        val result: Any? = source(input)
        val route = routes[result!!::class]
            ?: error("No branch defined for ${result::class.simpleName}.")
        return route(result)
    }
}
