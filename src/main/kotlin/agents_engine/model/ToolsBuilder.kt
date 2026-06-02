package agents_engine.model

import agents_engine.core.ToolPolicy
import agents_engine.generation.LenientJsonParser
import agents_engine.generation.constructFromMap
import agents_engine.generation.hasGenerableAnnotation
import agents_engine.generation.toLlmInput

@Suppress("UNCHECKED_CAST")
private fun mapInput(input: Map<String, Any?>): Map<String, Any?> = input

@PublishedApi
internal fun <Args> generableInputToMap(input: Args): Map<String, Any?> {
    val parsed = LenientJsonParser.parse(toLlmInput(input)) as? Map<*, *>
        ?: error("Tool input ${input?.let { it::class.simpleName } ?: "null"} did not encode to a JSON object")
    return parsed.entries.associate { (k, v) -> k.toString() to v }
}

/**
 * Names reserved for built-in memory tools, registered exclusively via
 * `Agent.memory(bank)`. User code cannot register these via the tools DSL
 * — would otherwise silently shadow the built-ins via `putIfAbsent` and be
 * auto-allowed by the agentic loop's memory path. See #644.
 */
@PublishedApi internal val RESERVED_MEMORY_TOOL_NAMES =
    setOf("memory_read", "memory_write", "memory_search")

@PublishedApi internal fun requireUserNotReservedToolName(name: String) {
    require(name !in RESERVED_MEMORY_TOOL_NAMES) {
        "Tool name \"$name\" is reserved for built-in memory tools (registered via memory(bank)). " +
            "Pick a different name."
    }
}

// #2804 — shared guard for the "Tool already defined in this tools block" check,
// previously duplicated 4× across the `tool(...)` overloads and the `unaryPlus`
// operator. Centralising it means a future cap change (e.g. case-insensitive
// uniqueness) touches one line.
@PublishedApi internal fun MutableList<ToolDef>.reserveName(name: String) {
    requireUserNotReservedToolName(name)
    require(none { it.name == name }) {
        "Tool \"$name\" is already defined in this tools block. " +
            "Tool names must be unique."
    }
}

class ToolsBuilder {
    @PublishedApi internal val defs = mutableListOf<ToolDef>()
    internal var defaultErrorHandler: ToolErrorHandler? = null

    fun defaults(block: ToolDefaultsBuilder.() -> Unit) {
        val builder = ToolDefaultsBuilder()
        builder.block()
        defaultErrorHandler = builder.errorHandler
    }

    fun tool(
        name: String,
        description: String,
        executor: (Map<String, Any?>) -> Any?,
    ): Tool<Map<String, Any?>, Any?> {
        defs.reserveName(name)
        val def = ToolDef(name = name, description = description, executor = executor)
        defs.add(def)
        return Tool(def, Map::class, Any::class, ::mapInput)
    }

    fun tool(
        name: String,
        description: String,
        onError: OnErrorBuilder.() -> Unit,
        executor: (Map<String, Any?>) -> Any?,
    ): Tool<Map<String, Any?>, Any?> {
        defs.reserveName(name)
        val def = ToolDef(name = name, description = description, executor = executor)
        def.errorHandler = OnErrorBuilder().apply(onError).build()
        defs.add(def)
        return Tool(def, Map::class, Any::class, ::mapInput)
    }

    fun tool(name: String, block: ToolDefBuilder.() -> Unit): Tool<Map<String, Any?>, Any?> {
        defs.reserveName(name)
        val builder = ToolDefBuilder(name)
        builder.block()
        val def = builder.build()
        defs.add(def)
        return Tool(def, Map::class, Any::class, ::mapInput)
    }

    operator fun ToolDef.unaryPlus() {
        defs.reserveName(this.name)
        defs.add(this)
    }

    /**
     * Typed tool builder — `tool<Args, Result>("name", "desc") { args -> ... }`.
     *
     * The framework wraps the typed `executor` in a `(Map<String, Any?>) -> Any?`
     * adapter that constructs `Args` from the incoming map via reflection
     * (`Args::class.constructFromMap(map)`). The resulting [ToolDef] carries
     * `argsType = Args::class` so downstream code (provider schema generation
     * in #635, runtime validation routing in #636) can introspect it.
     *
     * `Args` should be a `@Generable` data class — required fields enforce
     * presence, defaults are honored.
     */
    @JvmName("toolTyped")
    inline fun <reified Args : Any, Result> tool(
        name: String,
        description: String,
        policy: ToolPolicy? = null,
        crossinline executor: (Args) -> Result,
    ): Tool<Args, Result> {
        defs.reserveName(name)
        val argsClass = Args::class
        // #1718: route through the KSP-cache-aware probe so the check works
        // without kotlin-reflect on the classpath when KSP has generated the
        // companion. Falls through to wrapped reflection for non-KSP consumers.
        require(argsClass.hasGenerableAnnotation()) {
            "Typed tool \"$name\" Args type ${argsClass.simpleName} must be annotated with @Generable. " +
                "Add `@Generable(\"description\")` to the data class. " +
                "(If KSP is not applied and kotlin-reflect is not on the classpath, this check " +
                "cannot detect the annotation — add `ksp(\"ai.deep-code:agents-kt-ksp\")` to your build.)"
        }
        require(!argsClass.isSealed) {
            "Typed tool \"$name\" Args type ${argsClass.simpleName} is sealed. " +
                "Sealed Args (one-of variants) are not yet supported as tool inputs — " +
                "use a concrete data class for now."
        }
        val wrapped: (Map<String, Any?>) -> Any? = { rawArgs ->
            val typed = argsClass.constructFromMap(rawArgs)
                ?: error(
                    "Tool '$name' could not deserialize ${argsClass.simpleName} from arguments: $rawArgs"
                )
            executor(typed)
        }
        val def = ToolDef(
            name = name,
            description = description,
            executor = wrapped,
            argsType = argsClass,
            risk = policy?.risk ?: agents_engine.core.ToolRisk.LOW,
            policy = policy,
        )
        defs.add(def)
        return Tool(def, argsClass, Any::class, ::generableInputToMap)
    }
}
