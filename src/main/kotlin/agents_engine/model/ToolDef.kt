package agents_engine.model

import agents_engine.generation.Generable
import agents_engine.generation.constructFromMap
import kotlin.reflect.KClass
import agents_engine.generation.hasGenerableAnnotation

/**
 * `agents_engine/model/ToolDef.kt` — the `ToolDef` shape (wire-level
 * `Map<String, Any?> -> Any?` executor with optional session-aware
 * variant #1752), plus the `Tool<Args, Result>` typed handle (#1015 /
 * #1016) returned by `tool(...)` builders so `Skill.tools(...)` accepts
 * compile-time-checked refs. Includes typed builder overloads, the
 * `argsType` introspection slot, and the `untrustedOutput` flag for
 * sandboxed tool wiring. See
 * `src/main/resources/internals-agent/model/ToolDef.md` (#1837 / #1857).
 */

/**
 * A tool the agentic loop can invoke on the model's behalf.
 *
 * The wire signature is intentionally `Map<String, Any?> -> Any?` because that's
 * what the LLM actually sends and reads. For typed authoring, use the
 * `tool<Args, Result>("name") { args -> ... }` builder — it wraps your typed
 * lambda in a Map-shaped executor and records the [argsType] so downstream
 * consumers (provider schema generation, runtime validation) can introspect it.
 *
 * @property argsType the `@Generable` Args class for typed tools, `null` for
 *   tools authored via the legacy `tool(name, desc) { args: Map -> ... }` form.
 */
class ToolDef(
    val name: String,
    val description: String = "",
    val argsType: KClass<*>? = null,
    val untrustedOutput: Boolean = false,
    /**
     * #1752 — session-aware tool executor. When non-null AND the
     * agentic loop runs under a session (`emitter != null`), this is
     * used instead of [executor]. Allows tools that wrap a sibling
     * agent (Swarm absorb path) to stream the sibling's inner events
     * into the captain's session.
     *
     * Falls back to [executor] when null — preserves byte-for-byte
     * behavior for plain function tools and for non-streaming
     * invocations.
     *
     * Declared BEFORE [executor] so the trailing-lambda construction
     * `ToolDef(name, desc) { args -> ... }` still binds the lambda
     * to [executor].
     */
    val sessionExecutor: (suspend (Map<String, Any?>, agents_engine.model.AgentEventEmitter) -> Any?)? = null,
    val executor: (Map<String, Any?>) -> Any?,
) {
    var errorHandler: ToolErrorHandler? = null
        internal set
}

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
) {
    val name: String get() = def.name
    val description: String get() = def.description

    override fun toString(): String = "Tool<${def.name}>"
}

class ToolDefaultsBuilder {
    internal var errorHandler: ToolErrorHandler? = null

    fun onError(block: OnErrorBuilder.() -> Unit) {
        val builder = OnErrorBuilder()
        builder.block()
        errorHandler = builder.build()
    }
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
        requireUserNotReservedToolName(name)
        require(defs.none { it.name == name }) {
            "Tool \"$name\" is already defined in this tools block. " +
                "Tool names must be unique."
        }
        val def = ToolDef(name = name, description = description, executor = executor)
        defs.add(def)
        return Tool(def)
    }

    fun tool(
        name: String,
        description: String,
        onError: OnErrorBuilder.() -> Unit,
        executor: (Map<String, Any?>) -> Any?,
    ): Tool<Map<String, Any?>, Any?> {
        requireUserNotReservedToolName(name)
        require(defs.none { it.name == name }) {
            "Tool \"$name\" is already defined in this tools block. " +
                "Tool names must be unique."
        }
        val def = ToolDef(name = name, description = description, executor = executor)
        def.errorHandler = OnErrorBuilder().apply(onError).build()
        defs.add(def)
        return Tool(def)
    }

    fun tool(name: String, block: ToolDefBuilder.() -> Unit): Tool<Map<String, Any?>, Any?> {
        requireUserNotReservedToolName(name)
        require(defs.none { it.name == name }) {
            "Tool \"$name\" is already defined in this tools block. " +
                "Tool names must be unique."
        }
        val builder = ToolDefBuilder(name)
        builder.block()
        val def = builder.build()
        defs.add(def)
        return Tool(def)
    }

    operator fun ToolDef.unaryPlus() {
        requireUserNotReservedToolName(this.name)
        require(defs.none { it.name == this.name }) {
            "Tool \"${this.name}\" is already defined in this tools block. " +
                "Tool names must be unique."
        }
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
        crossinline executor: (Args) -> Result,
    ): Tool<Args, Result> {
        requireUserNotReservedToolName(name)
        require(defs.none { it.name == name }) {
            "Tool \"$name\" is already defined in this tools block. " +
                "Tool names must be unique."
        }
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
        val def = ToolDef(name = name, description = description, executor = wrapped, argsType = argsClass)
        defs.add(def)
        return Tool(def)
    }
}

class ToolDefBuilder(private val name: String) {
    private var desc: String = ""
    private var exec: ((Map<String, Any?>) -> Any?)? = null
    private var handler: ToolErrorHandler? = null
    private var untrusted: Boolean = false

    fun description(text: String) { desc = text }

    fun executor(block: (Map<String, Any?>) -> Any?) { exec = block }

    fun onError(block: OnErrorBuilder.() -> Unit) {
        handler = OnErrorBuilder().apply(block).build()
    }

    /**
     * Mark this tool's output as originating outside the agent's trust boundary
     * (network responses, user uploads, search results). The agentic loop will
     * wrap the result in a `ToolResultEnvelope` JSON with `trusted: false` before
     * injecting it into the LLM context, and the system prompt will warn the
     * model to treat such content as data rather than instructions. See #642.
     */
    fun untrustedOutput() { untrusted = true }

    internal fun build(): ToolDef {
        val def = ToolDef(
            name = name,
            description = desc,
            untrustedOutput = untrusted,
            executor = requireNotNull(exec) { "Tool \"$name\" must have an executor { } block." },
        )
        handler?.let { def.errorHandler = it }
        return def
    }
}

fun buildBuiltInTools(): List<ToolDef> = listOf(
    ToolDef(
        name = "escalate",
        description = "Signal that you cannot fix the problem. Args: reason (string), severity (LOW/MEDIUM/HIGH/CRITICAL, optional, defaults to HIGH).",
        executor = { args ->
            val reason = args["reason"]?.toString() ?: "Unknown reason"
            val severityStr = args["severity"]?.toString()?.uppercase() ?: "HIGH"
            val severity = try { Severity.valueOf(severityStr) } catch (_: Exception) { Severity.HIGH }
            throw EscalationException(reason, severity)
        },
    ),
    ToolDef(
        name = "throwException",
        description = "Signal a hard failure — the problem is fundamentally unrecoverable. Args: reason (string).",
        executor = { args ->
            val reason = args["reason"]?.toString() ?: "Unknown reason"
            throw ToolExecutionException(reason)
        },
    ),
)
