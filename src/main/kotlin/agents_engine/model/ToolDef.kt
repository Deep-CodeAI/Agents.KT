package agents_engine.model

class ToolDef(
    val name: String,
    val description: String = "",
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

class ToolsBuilder {
    internal val defs = mutableListOf<ToolDef>()
    internal var defaultErrorHandler: ToolErrorHandler? = null

    fun defaults(block: ToolDefaultsBuilder.() -> Unit) {
        val builder = ToolDefaultsBuilder()
        builder.block()
        defaultErrorHandler = builder.errorHandler
    }

    fun tool(name: String, description: String, executor: (Map<String, Any?>) -> Any?) {
        defs.add(ToolDef(name, description, executor))
    }

    fun tool(
        name: String,
        description: String,
        onError: OnErrorBuilder.() -> Unit,
        executor: (Map<String, Any?>) -> Any?,
    ) {
        val def = ToolDef(name, description, executor)
        def.errorHandler = OnErrorBuilder().apply(onError).build()
        defs.add(def)
    }

    fun tool(name: String, block: ToolDefBuilder.() -> Unit) {
        val builder = ToolDefBuilder(name)
        builder.block()
        val def = builder.build()
        defs.add(def)
    }
}

class ToolDefBuilder(private val name: String) {
    private var desc: String = ""
    private var exec: ((Map<String, Any?>) -> Any?)? = null
    private var handler: ToolErrorHandler? = null

    fun description(text: String) { desc = text }

    fun executor(block: (Map<String, Any?>) -> Any?) { exec = block }

    fun onError(block: OnErrorBuilder.() -> Unit) {
        handler = OnErrorBuilder().apply(block).build()
    }

    internal fun build(): ToolDef {
        val def = ToolDef(
            name,
            desc,
            requireNotNull(exec) { "Tool \"$name\" must have an executor { } block." },
        )
        handler?.let { def.errorHandler = it }
        return def
    }
}

fun buildBuiltInTools(): List<ToolDef> = listOf(
    ToolDef(
        "escalate",
        "Signal that you cannot fix the problem. Args: reason (string), severity (LOW/MEDIUM/HIGH/CRITICAL, optional, defaults to HIGH)."
    ) { args ->
        val reason = args["reason"]?.toString() ?: "Unknown reason"
        val severityStr = args["severity"]?.toString()?.uppercase() ?: "HIGH"
        val severity = try { Severity.valueOf(severityStr) } catch (_: Exception) { Severity.HIGH }
        throw EscalationException(reason, severity)
    },
    ToolDef(
        "throwException",
        "Signal a hard failure — the problem is fundamentally unrecoverable. Args: reason (string)."
    ) { args ->
        val reason = args["reason"]?.toString() ?: "Unknown reason"
        throw ToolExecutionException(reason)
    },
)
