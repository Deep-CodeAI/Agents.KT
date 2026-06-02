package agents_engine.model

/** Thrown by a repair agent to escalate a tool failure with a [Severity]; caught by the repair DSL. */
class EscalationException(val reason: String, val severity: Severity) : RuntimeException(reason)
