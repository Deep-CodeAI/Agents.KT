---
description: Source-file knowledge for agents_engine/nlweb/NlWebServer.kt — the server side of NLWeb (#4542, PRD §12.9). Exposes the NLWeb POST /ask contract over a loopback JDK HttpServer (same posture as McpServer/A2AServer: 127.0.0.1, optional bearer, gateway-required). Pure transport — an NlWebAskHandler does retrieval (back it with the RAG EmbeddingStore seam). renderAskResponse round-trips through the client's parseNlWebResponse. Call when the IDE LLM needs to reason about serving an agents.kt agent as an NLWeb endpoint.
---

# `agents_engine/nlweb/NlWebServer.kt` — serve an NLWeb endpoint (#4542)

The **serve** side of NLWeb; the `nlwebSearch` tool (#4541, `agents_engine/model/NlWebSearch.kt`) is the **consume** side. Exposes the NLWeb `POST /ask` contract so NLWeb clients (and other agents) can query an agents.kt retrieval source over a website-style natural-language interface.

```kotlin
val server = NlWebServer.from(handler = { req ->
    // back this with the RAG EmbeddingStore seam, an Agent, or anything: query -> ranked schema.org results
    NlWebSearchResult(results = retrieve(req.query, req.site), answer = null, queryId = null)
}, port = 8770, bearerToken = secret).start()
// server.url -> http://localhost:8770/ask ; server.stop() when done
```

## Posture — same as McpServer / A2AServer

Binds a JDK `HttpServer` to **`127.0.0.1` only** (never `0.0.0.0`); optional `Authorization: Bearer <token>` via `bearerToken`; front with a TLS-terminating gateway for any network reach. This is the deliberate inbound-endpoint exception (like `McpServer`/`A2AServer`), NOT the #4510 "no media server" stance — it's a standard-protocol serve surface, the third alongside MCP and A2A.

## Wire contract (v1)

- **Request:** `POST /ask` body `{query, site?, mode, streaming}`. `mode` ∈ `list`/`summarize`/`generate` (default `list`); `streaming` is accepted but ignored (single-blob reply; SSE is a follow-up). Missing/blank `query` → `400`. Non-POST → `405`. Bad JSON → `400`. Bad bearer → `401`. Handler throw → `500` with `{error}`.
- **Response:** `renderAskResponse(NlWebSearchResult)` → `{query_id, results:[{url, name?, site?, score?, description?, schema_object:{"@type":…}?}], summary?}`. `query_id` echoes the result's `queryId` or a fresh UUID; `summary` is the result's `answer` (summarize/generate). Null result fields are omitted.

## Key design

- **Pure transport.** The `NlWebAskHandler` (`fun interface`, `NlWebAskRequest -> NlWebSearchResult`) does all retrieval/ranking. The server owns only the HTTP + JSON mapping. Back the handler with the RAG `EmbeddingStore` seam (`:agents-kt-rag`, `query(RagQuery, topK) -> List<Match<T>>`), an `Agent`, or any source.
- **Symmetry guard.** `renderAskResponse` (serialize) round-trips through `parseNlWebResponse` (the client-side parser in `model/NlWebSearch.kt`) — tested, so serve and consume can't drift.
- **`/mcp` is not here.** Every NLWeb endpoint is also an MCP server; that face is `McpServer`'s job (expose an `ask` skill). `NlWebServer` is the `/ask`-over-HTTP path.

## Types (one-per-file, #3199)

`NlWebServer` + `renderAskResponse` (this file), `NlWebAskRequest`, `NlWebAskHandler` (in `agents_engine.nlweb`); `NlWebResult` / `NlWebSearchResult` / `NlWebMode` shared from `agents_engine.model`.

## Related files

- `A2AServer.kt` — the structural sibling (loopback JDK HttpServer + bearer; serve an agent over A2A).
- `model/NlWebSearch.kt` — the consume side (`nlwebSearchTool` + `parseNlWebResponse`).
