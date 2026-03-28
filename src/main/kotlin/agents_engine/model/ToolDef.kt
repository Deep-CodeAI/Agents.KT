package agents_engine.model

class ToolDef(
    val name: String,
    val description: String = "",
    val executor: (Map<String, Any?>) -> Any?,
)

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

    fun tool(name: String, description: String = "", executor: (Map<String, Any?>) -> Any?) {
        defs.add(ToolDef(name, description, executor))
    }

    // Convenience overload without description
    fun tool(name: String, executor: (Map<String, Any?>) -> Any?) {
        defs.add(ToolDef(name, "", executor))
    }
}
