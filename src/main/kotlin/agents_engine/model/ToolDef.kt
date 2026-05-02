package agents_engine.model

import agents_engine.generation.constructFromMap
import kotlin.reflect.KClass

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
    val executor: (Map<String, Any?>) -> Any?,
) {
    var errorHandler: ToolErrorHandler? = null
        internal set
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

    fun tool(name: String, description: String, executor: (Map<String, Any?>) -> Any?) {
        requireUserNotReservedToolName(name)
        require(defs.none { it.name == name }) {
            "Tool \"$name\" is already defined in this tools block. " +
                "Tool names must be unique."
        }
        defs.add(ToolDef(name = name, description = description, executor = executor))
    }

    fun tool(
        name: String,
        description: String,
        onError: OnErrorBuilder.() -> Unit,
        executor: (Map<String, Any?>) -> Any?,
    ) {
        requireUserNotReservedToolName(name)
        require(defs.none { it.name == name }) {
            "Tool \"$name\" is already defined in this tools block. " +
                "Tool names must be unique."
        }
        val def = ToolDef(name = name, description = description, executor = executor)
        def.errorHandler = OnErrorBuilder().apply(onError).build()
        defs.add(def)
    }

    fun tool(name: String, block: ToolDefBuilder.() -> Unit) {
        requireUserNotReservedToolName(name)
        require(defs.none { it.name == name }) {
            "Tool \"$name\" is already defined in this tools block. " +
                "Tool names must be unique."
        }
        val builder = ToolDefBuilder(name)
        builder.block()
        val def = builder.build()
        defs.add(def)
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
    ) {
        requireUserNotReservedToolName(name)
        require(defs.none { it.name == name }) {
            "Tool \"$name\" is already defined in this tools block. " +
                "Tool names must be unique."
        }
        val argsClass = Args::class
        val wrapped: (Map<String, Any?>) -> Any? = { rawArgs ->
            val typed = argsClass.constructFromMap(rawArgs)
                ?: error(
                    "Tool '$name' could not deserialize ${argsClass.simpleName} from arguments: $rawArgs"
                )
            executor(typed)
        }
        defs.add(ToolDef(name = name, description = description, executor = wrapped, argsType = argsClass))
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
