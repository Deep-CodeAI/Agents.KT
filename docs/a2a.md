# A2A — server + typed client (#3864)

Agents.KT agents participate in the cross-framework agent network: any `Agent<IN, OUT>` can be exposed over the A2A protocol (v0.2, JSON-RPC over HTTP), and any remote A2A endpoint becomes a typed `Agent<IN, OUT>` handle that drops into composition and skill allowlists like a local agent. The implementation follows the `McpServer` precedent — JDK `HttpServer`, loopback bind, optional bearer auth — no new dependencies.

## Server

```kotlin
val server = A2AServer.from(triageAgent, bearerToken = token).start()
// GET  /.well-known/agent-card.json   — AgentCard: name, skills, @Generable input schemas
// POST /a2a                           — JSON-RPC message/send
```

- The message's first text part becomes the agent's typed input: raw text for `String` IN, lenient-JSON-decoded for `@Generable` IN (one consistent IN type across skills; mixed-IN agents take raw text).
- The reply is a completed A2A Task whose artifact carries the output — JSON property map for typed OUT, raw text otherwise.
- Binds `127.0.0.1` only; front with a gateway for network reach (same guidance as MCP). `bearerToken` requires `Authorization: Bearer …` on every request.

## Typed client

```kotlin
val remoteBilling = a2aAgent<BillingTask, Resolution>(
    "billing", "https://acme.example.com/a2a/billing",
    bearerToken = System.getenv("ACME_TOKEN"),
)
val flow = triage then remoteBilling          // composes like any local agent
```

`a2aAgent<IN, OUT>` returns a real `Agent<IN, OUT>` whose single deterministic skill performs the round-trip. Remote failures surface as JSON-RPC errors → thrown with the remote message; HTTP/auth failures fail loud.

## Serving MCP and A2A side by side

The same agent instance can serve both protocols simultaneously — `McpServer.from(agent)` and `A2AServer.from(agent)` are independent wrappers that just invoke the agent (invocation is concurrent-safe, and neither server placement-marks it):

```kotlin
val mcp = McpServer.from(triage) { expose("classify"); auth = McpServerAuth.RequireBearerToken(t1) }.start()
val a2a = A2AServer.from(triage, bearerToken = t2).start()
```

Two things to know:

- **Two listeners, not one.** Each `from(...)` creates its own JDK `HttpServer` on its own loopback port; a gateway in front makes that invisible. (A single-port facade mounting `/mcp` + `/a2a` + the agent card is a natural follow-up — not built today.)
- **Different shapes of the same agent.** MCP exposes the agent's *skills as individual tools* (`expose(...)` narrows, per-client `toolPolicy` filters `tools/list`). A2A exposes the *agent as one typed `message/send` endpoint* — the agent's own routing decides internally; the AgentCard lists skills as metadata only. Same allowlists, budgets, and audit trail underneath; with `OtelTracePropagation.install()`, inbound calls on **both** protocols join the caller's distributed trace (#3873).

## v1 scope (follow-ups tracked on #3864)

- `message/send` only — `message/stream` (SSE over the #3866 session surface), `tasks/get` / `tasks/cancel`, and push notifications are not implemented; every task completes synchronously.
- Typed payloads are flat `@Generable`-style property maps; nested custom types render via `toString()`.
- No `traceparent` propagation yet — lands with native OTel (#3873).
