package agents_engine.core

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
