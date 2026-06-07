---
description: Source-file knowledge for agents_engine/model/PerplexityClient.kt — Perplexity (Sonar) Chat Completions adapter. A thin OpenAiClient subclass for api.perplexity.ai with provider identity `perplexity`, model ids sonar / sonar-pro / sonar-reasoning-pro / sonar-deep-research, and constrained decoding left ON (Perplexity accepts response_format.json_schema). Web-grounded search with citations is the separate perplexitySearch tool, not this connector. Call when the IDE LLM needs to reason about wiring the framework to Perplexity as a model.
---

# `agents_engine/model/PerplexityClient.kt` — Perplexity (Sonar) Chat Completions adapter

Perplexity exposes an OpenAI-format `POST /chat/completions` API at `api.perplexity.ai`, so the adapter subclasses `OpenAiClient` and keeps the message, tool-call, usage, and SSE parsing mechanics aligned with the OpenAI-compatible wire shape. Pattern mirrors `KimiClient` / `DeepSeekClient` / `OpenRouterClient`.

## Construction

```kotlin
agent<X, Y>("...") {
    model {
        perplexity("sonar")
        apiKey = java.nio.file.Files.readString(
            java.nio.file.Paths.get(".secrets", "perplexity-key"),
        ).trim()
        perplexityBaseUrl = "https://api.perplexity.ai"  // override for proxies / regional
        temperature = 0.7
        maxTokens = 4096
    }
}
```

## Provider Identity

Token usage and agent events report `provider = "perplexity"`; provider error envelopes surface with the `Perplexity` label (e.g. an invalid key returns `Perplexity returned an error: Invalid API key provided`).

## Model ids

`sonar` (lightweight grounded search), `sonar-pro` (advanced search + follow-ups), `sonar-reasoning-pro` (chain-of-thought reasoning), `sonar-deep-research` (exhaustive multi-source reports). There is no plain `sonar-reasoning`.

## Constrained decoding — ON

Unlike `DeepSeekClient` / `KimiClient` (which disable it), Perplexity accepts OpenAI's `response_format` with a `json_schema` payload, so `supportsConstrainedDecoding()` is left inherited (`true`). `@Generable` outputs can be requested as a strict schema.

## Connector vs. tool

This connector lets an agent reason *directly* on a sonar model. To get web-grounded, cited facts while an agent reasons on a **different** model (Claude/OpenAI/Ollama), use the `perplexitySearch` tool instead — see `PerplexitySearch.md`.

## Credentials

`.secrets/perplexity-key` (gitignored) or `PERPLEXITY_API_KEY` — the per-provider `.secrets/<provider>-key` convention. The factory error when `apiKey` is null points at `.secrets/perplexity-key`.
