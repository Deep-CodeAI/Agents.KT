package agents_engine.core

import kotlin.reflect.KClass

class Branch<IN, OUT>(
    private val source: Agent<IN, *>,
    private val routes: Map<KClass<*>, (Any?) -> OUT>,
) {
    operator fun invoke(input: IN): OUT {
        val result = (source as Agent<IN, Any?>)(input)
        val route = routes[result!!::class]
            ?: error("No branch defined for ${result::class.simpleName}.")
        return route(result)
    }
}

class BranchBuilder<OUT> {
    val routes = mutableMapOf<KClass<*>, (Any?) -> OUT>()

    inner class OnClause<T>(private val klass: KClass<*>) {
        @Suppress("UNCHECKED_CAST")
        infix fun then(agent: Agent<T, OUT>) {
            agent.markPlaced("branch")
            routes[klass] = { input -> agent(input as T) }
        }

        @Suppress("UNCHECKED_CAST")
        infix fun then(pipeline: Pipeline<T, OUT>) {
            routes[klass] = { input -> pipeline(input as T) }
        }
    }

    inline fun <reified T> on(): OnClause<T> = OnClause(T::class)
}

fun <IN, SEALED, OUT> Agent<IN, SEALED>.branch(block: BranchBuilder<OUT>.() -> Unit): Branch<IN, OUT> {
    val builder = BranchBuilder<OUT>()
    builder.block()
    return Branch(this, builder.routes)
}

// ─── Pipeline composition ───

infix fun <A, B, C> Agent<A, B>.then(other: Branch<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    return Pipeline(listOf(this)) { input -> other(this(input)) }
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Branch<B, C>): Pipeline<A, C> =
    Pipeline(agents) { input -> other(this(input)) }

infix fun <A, B, C> Branch<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    return Pipeline(listOf(other)) { input -> other(this(input)) }
}

infix fun <A, B, C> Branch<A, B>.then(other: Pipeline<B, C>): Pipeline<A, C> =
    Pipeline(other.agents) { input -> other(this(input)) }
