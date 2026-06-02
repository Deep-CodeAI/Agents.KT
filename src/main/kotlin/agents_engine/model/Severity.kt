package agents_engine.model

/** Severity of a tool-error escalation, consumed by [ToolError] and [EscalationException]. */
enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }
