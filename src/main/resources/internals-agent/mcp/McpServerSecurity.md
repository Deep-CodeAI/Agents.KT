---
description: Source-file knowledge for agents_engine/mcp/McpServerSecurity.kt — inbound MCP server auth and principal types. Defines ClientPrincipal, McpHttpRequestContext, McpAuthDecision, and McpServerAuth implementations: TrustedLocal, RequireBearerToken, RequireBearerTokens. Used by McpServer HTTP handling before JSON-RPC dispatch. Call when reasoning about MCP server authentication, Host/Origin policy, or per-client tool filtering.
---

# `agents_engine/mcp/McpServerSecurity.kt` — inbound MCP server security

Defines the small auth/principal surface used by `McpServer` before JSON-RPC dispatch.

## Types

- `ClientPrincipal` — authenticated caller identity. `ClientPrincipal.TrustedLocal` represents loopback/local-process use.
- `McpHttpRequestContext` — request view passed to auth implementations: headers plus remote address.
- `McpAuthDecision` — `Allow(principal)` or `Reject(statusCode, message)`.
- `McpServerAuth` — sealed inbound auth policy.

## Built-in auth modes

```kotlin
McpServerAuth.TrustedLocal
McpServerAuth.RequireBearerToken("token", ClientPrincipal("client-id"))
McpServerAuth.RequireBearerTokens(
    mapOf("token-a" to ClientPrincipal("a"), "token-b" to ClientPrincipal("b")),
)
```

`TrustedLocal` is the `McpServer` default: loopback callers are accepted and non-loopback callers are rejected. Bearer modes read the `Authorization: Bearer ...` header and reject missing or mismatched tokens with HTTP 401.

## How `McpServer` uses it

HTTP requests are authenticated before content-type validation and before JSON-RPC dispatch. The resulting `ClientPrincipal` is then passed to:

- `snapshotFor(principal)` / `initialize` capability filtering
- `tools/list` filtering
- `toolPolicy { principal, toolName -> ... }` for `tools/call`

Stdio uses `ClientPrincipal.TrustedLocal` because the trust boundary is the spawning process rather than an HTTP client.
