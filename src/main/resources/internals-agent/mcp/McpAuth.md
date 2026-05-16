# `agents_engine/mcp/McpAuth.kt` — auth scheme for MCP transports

A `sealed interface McpAuth` with two variants today: `None` (default) and `Bearer(token)`.

## Variants

```kotlin
sealed interface McpAuth {
    object None : McpAuth
    data class Bearer(val token: String) : McpAuth {
        override fun toString(): String = "Bearer(token=<redacted>)"
    }
}
```

## Bearer

- Sends `Authorization: Bearer <token>` on every HTTP request.
- `toString()` is redacted (`Bearer(token=<redacted>)`) to keep the token out of logs (#857). A `println(auth)` or `Throwable.message` referencing the instance won't leak the credential.
- `equals` / `hashCode` still hash the raw token via the data-class behavior — needed for cache keying.

## Stdio / TCP

The non-HTTP transports derive auth from connection identity:
- **Stdio** — the spawned subprocess inherits its parent's environment, so secrets live in env vars that the child reads. No `Authorization` header concept.
- **TCP** — sockets are typically inside a trust boundary (k8s pod-to-pod, localhost). When that's not the case, terminate TLS in front of the transport.

For now, those transports ignore `McpAuth` entirely.

## Why sealed

`sealed interface` rather than open class for two reasons:
- The framework can `when (auth)` exhaustively over variants — adding a new variant forces every consumer to handle it.
- Future variants (`OAuth(clientId, ...)`, `ApiKeyHeader(name, value)`, ...) plug in without overload sprawl on the transports.

## Future work

The KDoc notes future work for `CharArray`-backed tokens with explicit `close()` to wipe the memory. Today the framework holds the token as `String` for compatibility with data-class equality. Wiping is left as a follow-up because it requires the transport to know when to wipe (the lifecycle question is non-trivial).

## Related files

- `HttpMcpTransport.kt` — sets `Authorization: Bearer <token>` per request.
- `AgentMcpDsl.kt` — sets `auth = McpAuth.Bearer(...)` in the `server { }` block.
