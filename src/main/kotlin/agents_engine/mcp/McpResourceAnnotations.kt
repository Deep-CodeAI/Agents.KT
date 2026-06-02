package agents_engine.mcp

/** Server-provided hints about a resource (audience, priority, last-modified) (#1734). */
data class McpResourceAnnotations(
    /** Intended consumer(s): `"user"`, `"assistant"`, or both. */
    val audience: List<String>? = null,
    /** 0.0 (least important) to 1.0 (most important). */
    val priority: Double? = null,
    /** ISO 8601 timestamp of the resource's last modification. */
    val lastModified: String? = null,
)
