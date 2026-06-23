package agents_engine.ap2

/**
 * `agents_engine/ap2/Ap2Extension.kt` — AP2 (PRD §12.10) spike. AP2 is **advertised as an A2A extension**: a
 * participant lists the AP2 extension URI in its A2A AgentCard under `capabilities.extensions`, so a
 * counterparty discovers "this agent speaks AP2" the same way it discovers any A2A capability.
 *
 * The shipped `A2AServer` AgentCard is a plain map with a `capabilities` block (`streaming`/`pushNotifications`)
 * — this helper decorates that map with the AP2 extension entry, proving the advertisement shape without
 * changing the A2A API. (Production wiring = thread an `extensions` param through `A2AServer.from` /
 * `agentCard`.)
 */
object Ap2Extension {
    /** The AP2 A2A-extension URI advertised on the AgentCard. */
    const val URI: String = "https://github.com/google-agentic-commerce/ap2/v1"

    /** The extension descriptor object that goes in `capabilities.extensions`. */
    fun descriptor(): Map<String, Any?> = linkedMapOf(
        "uri" to URI,
        "description" to "Agent Payments Protocol — Intent/Cart mandate authorization (verify-first).",
        "required" to false,
    )

    /**
     * Return a copy of an A2A AgentCard [card] with the AP2 extension added to `capabilities.extensions`
     * (created if absent). Non-destructive; preserves existing capabilities.
     */
    @Suppress("UNCHECKED_CAST")
    fun advertiseOn(card: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap(card)
        val capabilities = LinkedHashMap((card["capabilities"] as? Map<String, Any?>) ?: emptyMap())
        val extensions = ((capabilities["extensions"] as? List<*>)?.toMutableList() ?: mutableListOf())
        extensions.add(descriptor())
        capabilities["extensions"] = extensions
        out["capabilities"] = capabilities
        return out
    }
}
