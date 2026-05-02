package agents_engine.composition.branch

import agents_engine.composition.pipeline.Pipeline
import agents_engine.core.*
import kotlin.reflect.KClass

class BranchBuilder<OUT> {
    @PublishedApi internal val routes = mutableListOf<BranchRoute<OUT>>()

    inner class OnClause<T : Any>(
        @PublishedApi internal val klass: KClass<T>,
        @PublishedApi internal val castFn: (Any?) -> T,
    ) {
        infix fun then(agent: Agent<T, OUT>) {
            agent.markPlaced("branch")
            routes += BranchRoute.TypeRoute(klass) { input -> agent.invokeSuspend(castFn(input)) }
        }

        infix fun then(pipeline: Pipeline<T, OUT>) {
            routes += BranchRoute.TypeRoute(klass) { input -> pipeline.invokeSuspend(castFn(input)) }
        }
    }

    /**
     * Marker for the `onNull then ...` clause: routes a null result from the source agent.
     * Place anywhere in the block; null routing is checked before type routing.
     */
    object OnNull
    /**
     * Marker for the `onElse then ...` clause: catches anything not matched by an
     * earlier `on<T>()` route. Acts as the sealed-hierarchy completeness escape hatch.
     */
    object OnElse

    val onNull: OnNull get() = OnNull
    val onElse: OnElse get() = OnElse

    infix fun OnNull.then(agent: Agent<*, OUT>) {
        @Suppress("UNCHECKED_CAST")
        (agent as Agent<Any?, OUT>).markPlaced("branch")
        routes += BranchRoute.NullRoute { _ -> @Suppress("UNCHECKED_CAST") (agent as Agent<Any?, OUT>).invokeSuspend(null) }
    }

    infix fun OnElse.then(agent: Agent<*, OUT>) {
        @Suppress("UNCHECKED_CAST")
        (agent as Agent<Any?, OUT>).markPlaced("branch")
        routes += BranchRoute.ElseRoute { input -> @Suppress("UNCHECKED_CAST") (agent as Agent<Any?, OUT>).invokeSuspend(input) }
    }

    inline fun <reified T : Any> on(): OnClause<T> = OnClause(T::class) { it as T }
}

fun <IN, SEALED : Any, OUT> Agent<IN, SEALED>.branch(block: BranchBuilder<OUT>.() -> Unit): Branch<IN, OUT> {
    val builder = BranchBuilder<OUT>()
    builder.block()
    validateSealedCompleteness(this.outType, builder.routes)
    return Branch(this, builder.routes)
}

/**
 * Overload for sources whose OUT may include null (modeled via type-system bypass —
 * Java interop, reflection-based @Generable construction, etc.). Lets `branch { onNull then ... }`
 * be expressible without forcing the source to declare a nullable OUT.
 */
@JvmName("branchNullable")
fun <IN, SEALED, OUT> Agent<IN, SEALED & Any>.branchNullable(block: BranchBuilder<OUT>.() -> Unit): Branch<IN, OUT> {
    return branch(block)
}

private fun <OUT> validateSealedCompleteness(sourceOutType: KClass<*>, routes: List<BranchRoute<OUT>>) {
    if (!sourceOutType.isSealed) return
    if (routes.any { it is BranchRoute.ElseRoute }) return  // onElse is the catch-all

    val coveredTypes = routes.filterIsInstance<BranchRoute.TypeRoute<OUT>>().map { it.klass }
    val sealedSubclasses = sourceOutType.sealedSubclasses
    val uncovered = sealedSubclasses.filter { sub -> coveredTypes.none { it.java.isAssignableFrom(sub.java) } }
    require(uncovered.isEmpty()) {
        "Branch on sealed type ${sourceOutType.simpleName} is missing routes for: " +
            "${uncovered.map { it.simpleName }}. Either add `on<X>() then ...` for each, " +
            "or add an `onElse then ...` catch-all."
    }
}
