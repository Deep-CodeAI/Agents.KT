package agents_engine.mcp

import java.net.InetAddress

/**
 * Authenticated caller identity for inbound MCP server requests.
 */
data class ClientPrincipal(
    val id: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    companion object {
        val TrustedLocal: ClientPrincipal = ClientPrincipal(
            id = "trusted-local",
            attributes = mapOf("transport" to "local"),
        )
    }
}

/**
 * Minimal HTTP request view exposed to [McpServerAuth] implementations.
 */
data class McpHttpRequestContext(
    val headers: Map<String, List<String>>,
    val remoteAddress: String?,
) {
    fun firstHeader(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}

sealed interface McpAuthDecision {
    data class Allow(val principal: ClientPrincipal) : McpAuthDecision
    data class Reject(val statusCode: Int, val message: String) : McpAuthDecision
}

/**
 * Inbound authentication policy for HTTP-hosted [McpServer] instances.
 *
 * The default [TrustedLocal] mode permits loopback clients only. Use
 * [RequireBearerToken] or [RequireBearerTokens] when the endpoint is reachable
 * from another process boundary or network segment.
 */
sealed interface McpServerAuth {
    fun authenticate(request: McpHttpRequestContext): McpAuthDecision

    object TrustedLocal : McpServerAuth {
        override fun authenticate(request: McpHttpRequestContext): McpAuthDecision =
            if (isLoopback(request.remoteAddress)) {
                McpAuthDecision.Allow(ClientPrincipal.TrustedLocal)
            } else {
                McpAuthDecision.Reject(401, "Unauthorized: non-local MCP requests require explicit server auth")
            }
    }

    data class RequireBearerToken(
        val token: String,
        val principal: ClientPrincipal = ClientPrincipal("bearer"),
    ) : McpServerAuth {
        init {
            require(token.isNotBlank()) { "Bearer token must not be blank." }
        }

        override fun authenticate(request: McpHttpRequestContext): McpAuthDecision =
            if (request.bearerToken() == token) {
                McpAuthDecision.Allow(principal)
            } else {
                McpAuthDecision.Reject(401, "Unauthorized: invalid or missing Bearer token")
            }
    }

    data class RequireBearerTokens(
        val tokens: Map<String, ClientPrincipal>,
    ) : McpServerAuth {
        init {
            require(tokens.isNotEmpty()) { "At least one Bearer token must be configured." }
            require(tokens.keys.none { it.isBlank() }) { "Bearer tokens must not be blank." }
        }

        override fun authenticate(request: McpHttpRequestContext): McpAuthDecision {
            val principal = request.bearerToken()?.let(tokens::get)
            return if (principal != null) {
                McpAuthDecision.Allow(principal)
            } else {
                McpAuthDecision.Reject(401, "Unauthorized: invalid or missing Bearer token")
            }
        }
    }

    companion object {
        private fun McpHttpRequestContext.bearerToken(): String? {
            val header = firstHeader("Authorization") ?: return null
            return header
                .takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substringAfter(' ')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        private fun isLoopback(remoteAddress: String?): Boolean {
            val host = remoteAddress ?: return false
            return runCatching { InetAddress.getByName(host).isLoopbackAddress }
                .getOrDefault(false)
        }
    }
}
