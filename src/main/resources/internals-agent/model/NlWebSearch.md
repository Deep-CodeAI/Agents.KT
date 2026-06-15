---
description: Source-file knowledge for agents_engine/model/NlWebSearch.kt — the nlwebSearch tool factory (#4541, PRD §12.9) that queries an NLWeb endpoint's /ask API over HTTP and folds schema.org results into an agent's context. untrustedOutput=true; pure buildNlWebAskBody/parseNlWebResponse helpers; injectable NlWebSearchBackend (default HttpNlWebSearchBackend, no API key). Call when the IDE LLM needs to reason about consuming NLWeb / agent↔web-content retrieval.
---

# `agents_engine/model/NlWebSearch.kt` — the `nlwebSearch` tool (#4541)

[NLWeb](https://github.com/nlweb-ai/NLWeb) gives a website a natural-language interface over its **schema.org**-structured content. `nlwebSearchTool(baseUrl)` is a `ToolDef` (mirroring `perplexitySearchTool`) that lets an agent on its OWN model query an NLWeb endpoint and fold the ranked, schema.org-typed results into context — the inbound, external-knowledge counterpart to MCP-tools.

```kotlin
agent<String, String>("researcher") {
    model { claude("claude-opus-4-7"); apiKey = anthropicKey }   // your own model
    tools { +nlwebSearchTool(baseUrl = "https://example.com",
                             options = NlWebSearchOptions(site = "podcasts", mode = NlWebMode.GENERATE)) }
    skills { /* … */ }
}
```

## Wire shape

- **Request:** POST `<baseUrl>/ask` with `{query, site?, mode, streaming:false}` (`buildNlWebAskBody`). `mode` ∈ `list` / `summarize` / `generate` (lowercased). `site` omitted when null.
- **Response (`parseNlWebResponse`):** `{query_id, results:[{url, name, site, score, description, schema_object}], summary?}`. Each result → `NlWebResult` (`schemaType` = `schema_object.@type`). `answer` ← top-level `summary` / `answer` (present in summarize/generate). A top-level `error` (string or `{message}`) raises `NlWebSearchException`.
- **No API key** — NLWeb endpoints are public. `baseUrl.trimEnd('/')` avoids a double slash before `/ask`.

## Security

- `untrustedOutput = true` (#642): fetched web content is wrapped in the `{trusted:false}` envelope and the model is warned to treat it as data, not instructions — NLWeb returns external website content, an injection vector. Same contract as `perplexitySearch`.
- On blank query or backend failure the executor returns an `"ERROR: …"` string (the agentic-loop tool-error convention), never throws.

## Result rendering

`NlWebSearchResult.render()` emits any `answer` first, then a numbered list of matches (`name (@type) — description` + url). This text is what feeds back to the model and lands in the JSONL audit row.

## Seams & types (one-per-file, #3199)

`NlWebSearchArgs` (`@Generable {query}`), `NlWebMode` (enum), `NlWebSearchOptions`, `NlWebResult`, `NlWebSearchResult`, `NlWebSearchBackend` (`fun interface` — inject in tests), `HttpNlWebSearchBackend` (default), `NlWebSearchException`. `buildNlWebAskBody` / `parseNlWebResponse` are pure + `internal` for hermetic unit tests.

## Two transports

This tool is the zero-wiring `/ask`-over-HTTP path. Because **every NLWeb endpoint is also an MCP server**, an NLWeb `/mcp` URL is equally consumable through the existing MCP client (`tools/call` the `ask` tool) — so an agents.kt agent can consume NLWeb either way.

## Related files

- `PerplexitySearch.kt` — the structural sibling (untrusted web-search tool with build/parse helpers + injectable backend).
- `ToolDef.kt` — `untrustedOutput`, `argsType`, `executor`.
