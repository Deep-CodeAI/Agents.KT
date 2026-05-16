package agents_engine.mcp

/**
 * `agents_engine/mcp/McpAuth.kt` — sealed auth scheme for MCP transports.
 * Today: `None` (default) and `Bearer(token)`. `Bearer.toString()` is
 * redacted to keep tokens out of logs (#857). Stdio + TCP derive auth
 * from connection identity; only HTTP currently cares. Sealed so future
 * variants (OAuth, ApiKeyHeader) plug in without overload sprawl. See
 * `src/main/resources/internals-agent/mcp/McpAuth.md` (#1837 / #1879).
 */

/**
 * Auth scheme for an MCP transport. Today only HTTP cares — stdio and TCP
 * derive auth from connection identity. Sealed so future variants
 * (`OAuth`, `ApiKeyHeader`, etc.) can plug in without overload sprawl.
 */
sealed interface McpAuth {
    /** No credentials sent. Default. */
    object None : McpAuth

    /**
     * Sends `Authorization: Bearer <token>` on every request.
     *
     * The token is held as a `String` for compatibility with `data class`
     * equality. **toString() is overridden to redact the token** so a
     * `println(auth)` or `Throwable.message` referencing this instance
     * doesn't leak the credential into logs (#857).
     *
     * Future work: store the token as `CharArray` and add an explicit
     * `close()` that wipes it. That requires a richer API surface (the
     * transport must know when to wipe) and is left as a follow-up.
     */
    data class Bearer(val token: String) : McpAuth {
        override fun toString(): String = "Bearer(token=<redacted>)"
    }
}
