package agents_engine.core

/** #2883/#2889 — thrown when an executor's env operation exceeds its declared policy. */
class ToolPolicyViolation(
    val toolName: String,
    val operation: String,
    val target: String,
    reason: String,
) : SecurityException("Tool \"$toolName\" $operation(\"$target\") denied: $reason")
