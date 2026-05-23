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

enum class ToolRisk {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
    UNKNOWN,

    ;

    val manifestName: String
        get() = when (this) {
            LOW -> "Low"
            MEDIUM -> "Medium"
            HIGH -> "High"
            CRITICAL -> "Critical"
            UNKNOWN -> "Unknown"
        }

    companion object {
        val Low: ToolRisk get() = LOW
        val Medium: ToolRisk get() = MEDIUM
        val High: ToolRisk get() = HIGH
        val Critical: ToolRisk get() = CRITICAL
        val Unknown: ToolRisk get() = UNKNOWN

        fun fromManifest(value: String?): ToolRisk =
            when (value?.trim()?.lowercase()) {
                "low" -> LOW
                "medium" -> MEDIUM
                "high" -> HIGH
                "critical" -> CRITICAL
                "unknown" -> UNKNOWN
                else -> UNKNOWN
            }
    }
}
