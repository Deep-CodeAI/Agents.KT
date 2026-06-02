package agents_engine.model

import kotlin.reflect.KClass

/**
 * Typed handle returned by every `tool(...)` builder overload. Wraps a
 * [ToolDef] with phantom type parameters that let `Skill.tools(...)` accept
 * compile-time-checked references instead of stringly-typed lookups
 * (#1015 — KSP P1.1).
 *
 * `Args` is the deserialized input type for typed tools (the `@Generable`
 * data class), `Map<String, Any?>` for untyped tools. `Result` is the lambda's
 * return type. Both type parameters are erased at runtime — the [def]
 * underneath is the canonical runtime representation.
 *
 * Not `@JvmInline value` because Kotlin prohibits vararg of value-class types,
 * and `Skill.tools(vararg refs: Tool<*, *>)` (#1016) is the primary use site.
 * Tool handles are constructed once per agent build, never on the hot path —
 * the per-handle allocation is negligible.
 */
class Tool<Args, Result> @PublishedApi internal constructor(
    @PublishedApi internal val def: ToolDef,
    override val inputType: KClass<*>,
    override val outputType: KClass<*>,
    private val inputAdapter: (Args) -> Map<String, Any?>,
) : agents_engine.core.Tool<Args, Result> {
    override val name: String get() = def.name
    override val description: String get() = def.description
    override val risk: agents_engine.core.ToolRisk get() = def.risk
    override val policy: agents_engine.core.ToolPolicy? get() = def.policy

    @Suppress("UNCHECKED_CAST")
    override suspend fun call(input: Args): Result =
        def.executor(inputAdapter(input)) as Result

    override fun toString(): String = "Tool<${def.name}>"
}
