package agents_engine.mcp

/**
 * DSL builder for [McpServer.from] — selects which skills to expose, configures inbound auth /
 * Host / Origin allowlists / per-principal tool policy, and registers server-side prompts (#1796)
 * and resources (#1810).
 */
class McpExposeBuilder internal constructor() {
    var port: Int = 0  // 0 = auto-assign
    /** Hard cap on inbound request body size. See #851. */
    var maxRequestBytes: Long = McpServer.DEFAULT_MAX_REQUEST_BYTES
    /** Inbound auth for HTTP-hosted McpServer requests. Stdio uses local process identity. */
    var auth: McpServerAuth = McpServerAuth.TrustedLocal
    /** Optional HTTP Host allowlist. Values may include or omit the port. Empty disables the check. */
    var allowedHosts: Set<String> = emptySet()
    /** Optional HTTP Origin allowlist. Empty disables the check for trusted local clients. */
    var originAllowlist: Set<String> = emptySet()
    internal val exposedNames = mutableListOf<String>()
    internal val prompts = mutableListOf<RegisteredPrompt>()
    internal var toolPolicy: (ClientPrincipal, String) -> Boolean = { _, _ -> true }

    fun expose(skillName: String) { exposedNames += skillName }

    fun toolPolicy(block: (principal: ClientPrincipal, toolName: String) -> Boolean) {
        toolPolicy = block
    }

    /**
     * #1796 — register a server-side prompt template. [render] is invoked per `prompts/get` call
     * with the client-supplied argument map; its String output becomes the prompt text returned to
     * the client.
     */
    fun prompt(
        name: String,
        description: String,
        arguments: List<McpPromptArgument> = emptyList(),
        render: (Map<String, Any?>) -> String,
    ) {
        require(prompts.none { it.name == name }) {
            "Prompt \"$name\" already registered on this McpServer."
        }
        prompts += RegisteredPrompt(name, description, arguments, render)
    }

    internal val resources = mutableListOf<RegisteredResource>()

    /**
     * #1810 — register a server-side resource. [content] is invoked per `resources/read` call; its
     * String return becomes the resource's text content. Use a static return for static resources;
     * pass a closure that reads from disk/db/etc. for dynamic content.
     */
    fun resource(
        uri: String,
        name: String,
        description: String? = null,
        mimeType: String? = null,
        content: () -> String,
    ) {
        require(resources.none { it.uri == uri }) {
            "Resource uri \"$uri\" already registered on this McpServer."
        }
        resources += RegisteredResource(uri, name, description, mimeType, content)
    }
}
