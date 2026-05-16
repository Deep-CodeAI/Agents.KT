package agents_engine.composition.branch

import agents_engine.composition.pipeline.Pipeline
import agents_engine.core.*
import agents_engine.generation.ReflectionFallback
import kotlin.reflect.KClass

class BranchBuilder<OUT> {
    @PublishedApi internal val routes = mutableListOf<BranchRoute<OUT>>()

    inner class OnClause<T : Any>(
        @PublishedApi internal val klass: KClass<T>,
        @PublishedApi internal val castFn: (Any?) -> T,
    ) {
        infix fun then(agent: Agent<T, OUT>) {
            agent.markPlaced("branch")
            routes += BranchRoute.TypeRoute(
                klass = klass,
                executor = { input -> agent.invokeSuspend(castFn(input)) },
                // #1748: stream the routed agent's events under branch.session.
                sessionExecutor = { input, emitter ->
                    agents_engine.runtime.events.runAgentInSession(agent, castFn(input), emitter).first
                },
                routedAgentName = agent.name,
            )
        }

        infix fun then(pipeline: Pipeline<T, OUT>) {
            routes += BranchRoute.TypeRoute(
                klass = klass,
                executor = { input -> pipeline.invokeSuspend(castFn(input)) },
                // #1748: pipeline's effectiveSessionExec streams the chain's events.
                sessionExecutor = { input, emitter ->
                    pipeline.effectiveSessionExec(castFn(input), emitter)
                },
                // Last agent in the pipeline produces the OUT, so use its name.
                routedAgentName = pipeline.agents.lastOrNull()?.name,
            )
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
        val a = agent as Agent<Any?, OUT>
        a.markPlaced("branch")
        routes += BranchRoute.NullRoute(
            executor = { _ -> a.invokeSuspend(null) },
            sessionExecutor = { _, emitter ->
                agents_engine.runtime.events.runAgentInSession(a, null, emitter).first
            },
            routedAgentName = a.name,
        )
    }

    infix fun OnElse.then(agent: Agent<*, OUT>) {
        @Suppress("UNCHECKED_CAST")
        val a = agent as Agent<Any?, OUT>
        a.markPlaced("branch")
        routes += BranchRoute.ElseRoute(
            executor = { input -> a.invokeSuspend(input) },
            sessionExecutor = { input, emitter ->
                agents_engine.runtime.events.runAgentInSession(a, input, emitter).first
            },
            routedAgentName = a.name,
        )
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
    // #1718: sealedSubclasses is a kotlin-reflect call. Wrap so a missing
    // kotlin-reflect skips the exhaustiveness check rather than crashing.
    // Consumers using Branch on sealed types should typically also have a
    // compile-time `when` on their side — losing this belt-and-braces check
    // is acceptable when reflect is intentionally absent.
    val sealedSubclasses = ReflectionFallback.withReflection {
        sourceOutType.sealedSubclasses
    } ?: return  // can't validate without reflection — skip the check, trust the consumer
    val uncovered = sealedSubclasses.filter { sub -> coveredTypes.none { it.java.isAssignableFrom(sub.java) } }
    require(uncovered.isEmpty()) {
        "Branch on sealed type ${sourceOutType.simpleName} is missing routes for: " +
            "${uncovered.map { it.simpleName }}. Either add `on<X>() then ...` for each, " +
            "or add an `onElse then ...` catch-all."
    }
}
