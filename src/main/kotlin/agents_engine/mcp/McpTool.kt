package agents_engine.mcp

import agents_engine.core.Tool
import agents_engine.core.ToolPolicy
import agents_engine.core.ToolRisk
import kotlin.reflect.KClass

/**
 * `agents_engine/mcp/McpTool.kt` — first-class typed tool handle for MCP
 * server tools. This is the MCP-native sibling to the local typed tool
 * handle in `agents_engine.model`: callers can keep using `toolSkills()`
 * for primary-skill wrapping, or use `McpClient.tools()` when they need a
 * tool-shaped boundary object for grants/manifests/policy (#1948).
 */
class McpTool<IN, OUT> internal constructor(
    private val client: McpClient,
    private val wireName: String,
    override val name: String,
    override val description: String,
    override val inputType: KClass<*>,
    override val outputType: KClass<*>,
    override val risk: ToolRisk = ToolRisk.UNKNOWN,
    override val policy: ToolPolicy? = null,
    private val inputAdapter: (IN) -> Map<String, Any?>,
    private val outputAdapter: (Any?) -> OUT,
) : Tool<IN, OUT> {

    override suspend fun call(input: IN): OUT =
        outputAdapter(client.call(wireName, inputAdapter(input)))

    override fun toString(): String = "McpTool<$name>"

    companion object {
        internal fun mapTool(
            client: McpClient,
            descriptor: McpToolDescriptor,
            displayName: String,
            description: String,
        ): McpTool<Map<String, Any?>, String> =
            McpTool(
                client = client,
                wireName = descriptor.name,
                name = displayName,
                description = description,
                inputType = Map::class,
                outputType = String::class,
                risk = descriptor.annotations.toRisk(),
                policy = null,
                inputAdapter = { it },
                outputAdapter = { it?.toString() ?: "" },
            )
    }
}

private fun McpToolAnnotations?.toRisk(): ToolRisk = when {
    this == null -> ToolRisk.UNKNOWN
    destructiveHint == true -> ToolRisk.HIGH
    openWorldHint == true -> ToolRisk.MEDIUM
    readOnlyHint == true -> ToolRisk.LOW
    else -> ToolRisk.UNKNOWN
}
