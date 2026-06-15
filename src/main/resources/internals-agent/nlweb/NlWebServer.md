---
description: Source-file knowledge for agents_engine/nlweb/NlWebServer.kt — the server side of NLWeb (#4542, PRD §12.9). NlWebServer.from(agent) exposes the NLWeb POST /ask contract over a loopback JDK HttpServer (same from(agent) shape + posture as McpServer/A2AServer: 127.0.0.1, optional bearer, gateway-required). The query is the agent input; NlWebSearchResult output is served verbatim, any other output becomes the summary. renderAskResponse round-trips through the client's parseNlWebResponse. Call when the IDE LLM needs to reason about serving an agent as an NLWeb endpoint.
---

# `agents_engine/nlweb/NlWebServer.kt` — serve an NLWeb endpoint (#4542)

The **serve** side of NLWeb; the `nlwebSearch` tool (#4541, `agents_engine/model/NlWebSearch.kt`) is the **consume** side. `from(agent)` — the same DX as `McpServer.from(agent)` / `A2AServer.from(agent)` (no bespoke handler type; an agent is the unit).

```kotlin
// the agent IS the retrieval engine — back its skills with the RAG EmbeddingStore seam, etc.
val server = NlWebServer.from(agent, port = 8770, bearerToken = secret).start()
// server.url -> http://localhost:8770/ask ; server.stop() when done
```

## How output maps to the NLWeb response

The `/ask` `query` string is the agent's input. `runBlocking { agent.invokeSuspend(query) }`, then:
- output `is NlWebSearchResult` → served **verbatim** (ranked schema.org `results[]` + optional `summary`).
- any other output → `{results: [], summary: output.toString()}` (the generate/answer shape).

So an agent that retrieves structured matches returns `NlWebSearchResult`; a plain Q&A agent returns a `String` and the server surfaces it as the answer.

## Posture — same as McpServer / A2AServer

Binds a JDK `HttpServer` to **`127.0.0.1` only**; optional `Authorization: Bearer <token>`; front with a TLS gateway for network reach. Deliberate inbound-endpoint exception (like MCP/A2A), NOT the #4510 "no media server" stance — it's a standard-protocol serve surface, the third alongside MCP and A2A.

## Wire contract (v1)

- **Request:** `POST /ask` `{query, site?, mode, streaming}`. Only `query` is consumed (the agent input); `site`/`mode`/`streaming` are accepted but not branched on in v1 (SSE is a follow-up). Missing/blank `query` → `400`. Non-POST → `405`. Bad JSON → `400`. Bad bearer → `401`. Agent throw → `500` `{error}`.
- **Response:** `renderAskResponse(NlWebSearchResult)` → `{query_id, results:[{url, name?, site?, score?, description?, schema_object:{"@type":…}?}], summary?}`. `query_id` echoes the result's `queryId` or a fresh UUID; null fields omitted.

## Key design

- **No bespoke seam.** Earlier drafts had an `NlWebAskHandler`/`NlWebAskRequest`; dropped for DX — `from(agent)` matches the rest of the serve surface, and the agent (with its skills/RAG knowledge) is the retrieval engine. Fewer new terms.
- **Symmetry guard.** `renderAskResponse` (serialize) round-trips through `parseNlWebResponse` (the client-side parser in `model/NlWebSearch.kt`) — tested, so serve and consume can't drift.
- **`/mcp` is not here.** Every NLWeb endpoint is also an MCP server; that face is `McpServer`'s job (expose an `ask` skill). `NlWebServer` is the `/ask`-over-HTTP path.

## Related files

- `A2AServer.kt` — the structural sibling (`from(agent)`, loopback JDK HttpServer + bearer).
- `model/NlWebSearch.kt` — the consume side (`nlwebSearchTool` + `parseNlWebResponse`); `NlWebResult` / `NlWebSearchResult` / `NlWebMode` live there.
