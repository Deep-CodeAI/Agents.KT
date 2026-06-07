---
description: Source-file knowledge for agents_engine/model/PerplexitySearch.kt — the perplexitySearch tool (#3676/#3677). A ToolDef (untrustedOutput=true) that lets an agent on its own model fetch web-grounded, cited facts from Perplexity's Sonar API. Covers PerplexitySearchArgs/Source/Result/Options, the PerplexitySearchBackend seam + HttpPerplexitySearchBackend, pure buildPerplexitySearchBody/parsePerplexitySearchResponse, and the perplexitySearchOptions { } controls DSL (search_mode/recency/domain/context-size/reasoning-effort/structured-output). Call when the IDE LLM needs to reason about adding web-grounded search to an agent.
---

# `agents_engine/model/PerplexitySearch.kt` — the `perplexitySearch` tool

A `ToolDef` (not a model) that lets an agent reasoning on its **own** model (Claude/OpenAI/Ollama/…) fetch live, cited facts from Perplexity's Sonar API. Distinct from `PerplexityClient`, which makes a sonar model the agent's reasoner.

## Register

```kotlin
agent<String, String>("researcher") {
    model { claude("claude-opus-4-7"); apiKey = anthropicKey }
    tools { +perplexitySearchTool(perplexityKey) }
}
```

`perplexityKey` from `.secrets/perplexity-key` / `PERPLEXITY_API_KEY`. The tool is usable without a Perplexity *model* configured.

## Security — untrusted by construction

The tool is `untrustedOutput = true`. The agentic loop (AgenticLoop.kt:710) renders the result via `ToolResultRendering.renderToolResultForLlm` then wraps it with `wrapUntrustedToolResult` → `{"tool":"perplexity_search","trusted":false,"value":"…"}`, and the system prompt warns the model to treat it as data, not instructions (#642). Web search is the canonical prompt-injection vector.

## Shapes

- `PerplexitySearchArgs(query)` — `@Generable`, the single tool argument.
- `PerplexitySource(url, title?, snippet?, date?)` — one grounded source.
- `PerplexitySearchResult(answer, sources)` — `toString()`/`render()` emits the answer then a numbered source list. This is the text the loop feeds back (and records in the JSONL audit row).
- `PerplexitySearchOptions(model, mode, recency, domainsAllow, domainsDeny, contextSize, reasoningEffort, jsonSchema)` — all controls default to "unset" → omitted from the request; a bare options object reproduces the minimal `model` + `messages` body.

## Backend seam

`fun interface PerplexitySearchBackend { search(query, options): PerplexitySearchResult }`. Default `HttpPerplexitySearchBackend` POSTs to `<baseUrl>/chat/completions`. Inject a fake in tests. `perplexitySearchTool(apiKey, options, baseUrl, backend)` returns `"ERROR: …"` strings on blank query / backend failure (the loop's tool-error convention).

## Pure helpers (unit-testable, no network)

- `buildPerplexitySearchBody(query, options)` — assembles the request, serializing the #3677 controls: `search_mode` (web/academic/sec), `search_recency_filter` (hour…year), `search_domain_filter` (allow plain + deny with a leading `-`), `web_search_options.search_context_size`, `reasoning_effort`, and `response_format` json_schema.
- `parsePerplexitySearchResponse(rawJson)` — `answer` ← `choices[0].message.content`; `sources` ← `search_results[]` (title/url/snippet/date) preferred, `citations[]` URLs as fallback; a top-level `error` envelope raises `PerplexitySearchException`.

## Controls DSL (#3677)

```kotlin
+perplexitySearchTool(perplexityKey, perplexitySearchOptions {
    model = "sonar-pro"
    mode = SearchMode.ACADEMIC          // search_mode
    recency = SearchRecency.WEEK        // search_recency_filter
    allowDomains("arxiv.org")           // search_domain_filter
    denyDomains("reddit.com")           // → "-reddit.com"
    contextSize = SearchContextSize.HIGH
    structuredOutput(MyFacts::class)    // response_format json_schema from a @Generable type
})
```

`reasoningEffort` reuses the shared `ReasoningEffort` enum (LOW/MEDIUM/HIGH); the API's `minimal` is not modeled.
