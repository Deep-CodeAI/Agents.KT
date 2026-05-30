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

// #2805 — manifestName is now a primary-ctor field, so fromManifest derives
// from `entries` instead of a parallel `when`. One representation, one
// source of drift to worry about.
enum class ToolRisk(val manifestName: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    CRITICAL("Critical"),
    UNKNOWN("Unknown"),
    ;

    companion object {
        // DSL ergonomic aliases — `risk = ToolRisk.Medium` reads more naturally
        // than `risk = ToolRisk.MEDIUM` inside the `policy { }` builder. Kept
        // intentionally; documented in internals adjuncts for Tool.md /
        // ToolPolicy.md / ToolDef.md.
        val Low: ToolRisk get() = LOW
        val Medium: ToolRisk get() = MEDIUM
        val High: ToolRisk get() = HIGH
        val Critical: ToolRisk get() = CRITICAL
        val Unknown: ToolRisk get() = UNKNOWN

        fun fromManifest(value: String?): ToolRisk {
            val key = value?.trim()?.lowercase() ?: return UNKNOWN
            return entries.firstOrNull { it.manifestName.lowercase() == key } ?: UNKNOWN
        }
    }
}
