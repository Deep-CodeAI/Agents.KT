package agents_engine.composition.branch

import agents_engine.core.*
import agents_engine.composition.pipeline.Pipeline
import kotlin.reflect.KClass

class BranchBuilder<OUT> {
    val routes = mutableMapOf<KClass<*>, (Any?) -> OUT>()

    inner class OnClause<T : Any>(
        private val klass: KClass<T>,
        private val castFn: (Any?) -> T,
    ) {
        infix fun then(agent: Agent<T, OUT>) {
            agent.markPlaced("branch")
            routes[klass] = { input -> agent(castFn(input)) }
        }

        infix fun then(pipeline: Pipeline<T, OUT>) {
            routes[klass] = { input -> pipeline(castFn(input)) }
        }
    }

    inline fun <reified T : Any> on(): OnClause<T> = OnClause(T::class) { it as T }
}

fun <IN, SEALED, OUT> Agent<IN, SEALED>.branch(block: BranchBuilder<OUT>.() -> Unit): Branch<IN, OUT> {
    val builder = BranchBuilder<OUT>()
    builder.block()
    return Branch(this, builder.routes)
}
