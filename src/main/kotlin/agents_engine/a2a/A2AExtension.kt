package agents_engine.a2a

/**
 * #3864 — an [A2A protocol extension](https://a2a-protocol.org/latest/topics/extensions/) advertised on the
 * AgentCard's `capabilities.extensions`. A counterparty discovers that this agent speaks an extension (e.g.
 * Google's **AP2** agent-payments mandate layer — PRD §12.10) by finding its `uri` there, the same way it
 * discovers `streaming` / `pushNotifications`.
 *
 * Pass these to [A2AServer.from] (`extensions = …`); advertising a capability is declarative and does not change
 * the JSON-RPC surface — the extension's own messages ride inside the normal A2A conversation.
 *
 * @property uri the extension's identifying URI (required).
 * @property description human-readable note (optional; omitted from the card when null).
 * @property required whether a client MUST understand the extension to interact (defaults to false).
 */
data class A2AExtension(
    val uri: String,
    val description: String? = null,
    val required: Boolean = false,
) {
    /** The A2A wire object for `capabilities.extensions[]` (omits `description` when absent). */
    internal fun toJsonObject(): Map<String, Any?> = linkedMapOf<String, Any?>("uri" to uri).apply {
        description?.let { put("description", it) }
        put("required", required)
    }
}
