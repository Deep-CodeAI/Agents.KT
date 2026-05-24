# MCP Server Hardening

`McpServer.from(agent)` can be used as a local IDE bridge or as an internal HTTP service. Treat those as different trust boundaries.

## Local mode

By default, HTTP `McpServer` uses `McpServerAuth.TrustedLocal`: loopback callers are accepted and non-loopback callers are rejected. This keeps local `localhost` integrations simple while avoiding an accidental unauthenticated network listener.

```kotlin
val server = McpServer.from(agent) {
    port = 8765
    expose("read_docs")
}.start()
```

Use stdio for spawned desktop clients when possible:

```kotlin
McpStdioServer.from(agent) {
    expose("read_docs")
}.serve()
```

## Bearer-authenticated HTTP

Use explicit bearer auth when the endpoint is reachable outside the local process boundary.

```kotlin
val server = McpServer.from(agent) {
    port = 8765
    expose("read_docs")
    expose("write_docs")

    auth = McpServerAuth.RequireBearerTokens(
        mapOf(
            requireNotNull(System.getenv("MCP_READ_TOKEN")) to ClientPrincipal("readonly"),
            requireNotNull(System.getenv("MCP_WRITE_TOKEN")) to ClientPrincipal("writer"),
        ),
    )
    allowedHosts = setOf("agents.internal.example")
    originAllowlist = setOf("https://ide.internal.example")
    toolPolicy { principal, toolName ->
        principal.id == "writer" || toolName == "read_docs"
    }
}
```

Security behavior:

- Missing or wrong bearer tokens return HTTP 401 before JSON-RPC dispatch.
- Missing or mismatched `Host` / `Origin` values return HTTP 403 when allowlists are configured.
- Denied tools are removed from `tools/list`.
- Denied `tools/call` returns JSON-RPC `-32601` without naming the denied tool.
- `server.snapshotFor(principal)` returns the same filtered capability view used by `initialize`.

## Nginx

Run the JVM on loopback and let Nginx terminate TLS, rate-limit, and forward only valid traffic.

```nginx
server {
  listen 443 ssl;
  server_name agents.internal.example;

  ssl_certificate     /etc/letsencrypt/live/agents/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/agents/privkey.pem;

  location /mcp {
    limit_req zone=mcp burst=20 nodelay;
    proxy_set_header Host agents.internal.example;
    proxy_set_header Origin https://ide.internal.example;
    proxy_set_header Authorization $http_authorization;
    proxy_pass http://127.0.0.1:8765/mcp;
  }
}
```

## Envoy mTLS

For service-mesh deployments, terminate mTLS at Envoy and forward a short-lived bearer token or map client certificate identity to a per-client token at the edge.

```yaml
static_resources:
  listeners:
    - name: mcp_https
      address:
        socket_address: { address: 0.0.0.0, port_value: 443 }
      filter_chains:
        - transport_socket:
            name: envoy.transport_sockets.tls
            typed_config:
              "@type": type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext
              require_client_certificate: true
          filters:
            - name: envoy.filters.network.http_connection_manager
              typed_config:
                "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
                route_config:
                  virtual_hosts:
                    - name: mcp
                      domains: ["agents.internal.example"]
                      routes:
                        - match: { prefix: "/mcp" }
                          route: { cluster: agents_kt_mcp }
```

Keep `McpServer` itself bound to loopback behind Envoy and keep its `auth`, `allowedHosts`, `originAllowlist`, and `toolPolicy` enabled. Gateway auth and in-process policy are defense in depth, not substitutes for each other.

## Anti-patterns

- Binding `McpServer` to a public interface with `TrustedLocal` semantics.
- Disabling Host/Origin checks for browser-reachable deployments.
- Exposing every agent skill with `expose(...)` and relying on descriptions for safety.
- Returning different error messages for denied versus unknown sensitive tools.
- Treating MCP auth as a replacement for OS/container sandboxing of tool bodies.
