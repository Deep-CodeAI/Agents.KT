# Agents.KT v0.7.24 — Perplexity: web-grounded search with citations

**Release date:** 2026-06-10

0.7.24's headline is the **Perplexity connector** and the **`perplexitySearch` tool**: agents can now reach the Sonar models directly via `model { perplexity("sonar") }`, or stay on their own model and fetch live, cited web facts through a typed search tool that records both the answer and its sources in the audit row. Plus a docs-accuracy pass that fixes false-negative roadmap signals, a small detekt-baseline reduction, and a Kotlin 2.4.0 upgrade.

Drop-in on the 0.7.x line — additive only, no public-API change to existing surfaces.

## Added — Perplexity connector + web-grounded search (epic #3674)

### `PerplexityClient` — seventh model provider (#3675)

A thin OpenAI-compatible `OpenAiClient` subclass for `api.perplexity.ai`, mirroring `DeepSeekClient` / `KimiClient` / `OpenRouterClient`. Selectable via `model { perplexity("sonar") }`; supports `sonar` / `sonar-pro` / `sonar-reasoning-pro` / `sonar-deep-research`. Unlike Kimi and DeepSeek, Perplexity accepts OpenAI's `response_format` json_schema, so its constrained-decoding gate stays **on** — typed `@Generable` outputs work end-to-end.

`ModelProvider`, `ModelConfig.perplexityBaseUrl`, `ModelBuilder.perplexity(...)`, the factory dispatch, and the permission manifest are all wired. Key from `.secrets/perplexity-key` or `PERPLEXITY_API_KEY` env.

```kotlin
val analyst = agent<String, MarketBrief>("analyst") {
    model { perplexity("sonar-deep-research"); apiKey = System.getenv("PERPLEXITY_API_KEY") }
    skills {
        skill<String, MarketBrief>("brief", "Produce a typed market brief from a query") {
            tools(/* no tools — use Perplexity's own web access */)
        }
    }
}
```

### `perplexitySearch` tool — web-grounded search with citations (#3676)

Lets an agent on its **own** model (Claude, OpenAI, Ollama, anything) reach into Perplexity for live, cited facts. `tools { +perplexitySearchTool(key) }` registers it; `untrustedOutput = true` so results are wrapped in the `{"trusted":false}` envelope per #642 and flagged as data, not instructions.

The result renders the answer plus a numbered source list parsed from `search_results[]` (falling back to `citations[]`); sources reach both the model context and the JSONL audit row, so the audit lane carries the provenance of every cited fact.

```kotlin
val researcher = agent<String, String>("researcher") {
    model { claude("claude-opus-4-7"); apiKey = anthropicKey }
    tools { +perplexitySearchTool(perplexityKey) }
    skills {
        skill<String, String>("research", "Research with citations") { tools("perplexitySearch") }
    }
}
```

### Search controls + structured output (#3677)

`perplexitySearchOptions { }` maps directly to Perplexity's documented request params:

- `search_mode` — `web` / `academic` / `sec`
- `search_recency_filter`
- `search_domain_filter` — allowlist + `-`-prefixed denylist
- `web_search_options.search_context_size` — `low` / `medium` / `high`
- `reasoning_effort`
- Native `response_format` json_schema via `structuredOutput(MyType::class)` from a `@Generable` type

### `chatCompletionsPath` seam on `OpenAiClient` (#3675)

The chat-completions path is now overridable (default `/v1/chat/completions`); `PerplexityClient` overrides to `/chat/completions` — Perplexity serves no `/v1` segment and hitting `/v1` there 404s with an empty body. Behavior unchanged for OpenAI / DeepSeek / Kimi / OpenRouter.

## Docs — accurate shipped signals

External gap analysis surfaced false-negative signals in the roadmap — multimodal / reactive-UI streaming / the session model were marked unchecked while the README and shipped code said otherwise. The roadmap even contradicted itself: `AgentSession.events` shown as shipped on line 78 and not-shipped on lines 73 / 83. Cleaned up so docs (and future AI consumers reading the repo) get correct signals:

- Multi-turn `AgentSession` (#1736) — marked shipped.
- `AgentSession.events` `Flow<AgentEvent>` (#1736) + `agent.observe { }` (#965) — marked shipped.
- Vision / document multimodal input across Anthropic / OpenAI / Ollama — marked shipped.
- Remaining open items (automatic compaction, Pipeline-stage event types) left as `[ ]`.

## Refactored — one type per file burndown complete (#3199)

`PerplexitySearch.kt` was the last multi-type file. Split into one type per file (`Args` / `Source` / `Result` / `Options` / `OptionsBuilder` / `Backend` / `HttpBackend` / `Exception` + `SearchMode` / `Recency` / `ContextSize`), keeping only the pure wire helpers and the `perplexitySearchTool` factory in `PerplexitySearch.kt`. The `checkOneTypePerFile` guard's allowlist is now empty — future commits cannot reintroduce multi-type files without failing CI.

## Maintainability — detekt baseline 415 → 410

Five real cleanups, no mechanical wraps. Replaced inline `kotlinx.coroutines.flow.FlowCollector` FQNs with imports in `ClaudeClient` / `OpenAiClient` SSE parsers, wrapped two over-long lines that read better wrapped, converted `MockTcpMcpServer`'s unused `private val acceptThread` into an `init { }` block (same start-on-construction, no retained handle). The bulk of the remaining MaxLineLength baseline is intentional — table-aligned test fixtures and inline JSON wire-templates that read worse if wrapped — and was left alone.

## Dependencies

- **Kotlin 2.3.21 → 2.4.0** — compiler, stdlib, reflect across every module + KSP.
- **`org.jline:jline` 3.27.1 → 4.1.3.**
- **detekt 1.23.7 → 1.23.8.**
- **KSP API 2.3.7 → 2.3.9** — matches Kotlin 2.4.0.

## Compatibility

Drop-in on the 0.7.x line. The Perplexity surface is additive; no public-API change to existing connectors, the agentic loop, or the audit boundary. The Kotlin 2.4.0 upgrade is binary-compatible for consumers on Kotlin 2.3.x or 2.4.x; existing tests pass byte-for-byte.

## What's not in this release

- Pipeline-stage event types in the streaming surface — still pending.
- Automatic conversation compaction in `AgentSession` — still pending.
- Closing #2791 (the turn-loop core of `executeAgentic`) — last open child of the #2790 maintainability epic, deliberately deferred as the highest-risk refactor.
