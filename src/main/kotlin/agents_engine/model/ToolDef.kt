package agents_engine.model

class ToolDef(
    val name: String,
    val description: String = "",
    val executor: (Map<String, Any?>) -> Any?,
)

class ToolsBuilder {
    internal val defs = mutableListOf<ToolDef>()

    fun tool(name: String, description: String = "", executor: (Map<String, Any?>) -> Any?) {
        defs.add(ToolDef(name, description, executor))
    }

    // Convenience overload without description
    fun tool(name: String, executor: (Map<String, Any?>) -> Any?) {
        defs.add(ToolDef(name, "", executor))
    }
}
