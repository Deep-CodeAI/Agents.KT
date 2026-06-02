package agents_engine.core

import kotlin.reflect.KClass

/**
 * `agents_engine/core/Tool.kt` — provider-neutral typed tool contract.
 * Local DSL tools and MCP tools can both implement this shape so future
 * permission manifests, grants, audit, and policy code can reason over one
 * boundary primitive instead of parallel local/MCP concepts (#1948).
 */
interface Tool<IN, OUT> {
    val name: String
    val description: String
    val inputType: KClass<*>
    val outputType: KClass<*>
    val risk: ToolRisk
    val policy: ToolPolicy?

    suspend fun call(input: IN): OUT
}
