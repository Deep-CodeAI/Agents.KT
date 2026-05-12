# Agents.KT v0.4.0 — Three Providers

**Release date:** 2026-05-12

One adapter was already in the box. Two more land here. Switching providers is now a one-line change.

## What's new

### Three model providers, one `ModelClient`

```kotlin
// Local / cloud Ollama (since 0.1)
model { ollama("qwen2.5:7b"); host = "localhost"; port = 11434 }

// Anthropic — new in 0.4
model { claude("claude-opus-4-7"); apiKey = System.getenv("ANTHROPIC_API_KEY") }

// OpenAI Chat Completions — new in 0.4
model { openai("gpt-4o"); apiKey = System.getenv("OPENAI_API_KEY") }
```

`LlmMessage` / `LlmResponse` are provider-agnostic. Each adapter handles the provider's own conventions internally — Anthropic's structured `tool_use` / `tool_result` content blocks, OpenAI's stringified `function.arguments` and synthesized `tool_call_id`s, Ollama's flat shape with inline-JSON fallback. The agentic loop on top is unchanged.

Both new adapters share the same boundary contract `OllamaClient` established: top-level provider error envelopes surface as `LlmProviderException`, not garbled text masquerading as model output (`#702`).

### Fail-fast at REPL startup

`LiveShowBuilder.precheck: (() -> Unit)?` runs after argument parsing and before the banner / `--once` / REPL prompt. Throw to abort; the runner prints `error: <msg>` and returns exit code 2. No more mid-spinner `java.net.ConnectException` on the first turn.

```kotlin
LiveRunner.serve(captain, args) {
    prompt = "fib> "
    precheck = OllamaPreflight(host = "localhost", port = 11434)::check
}
```

`OllamaPreflight` ships in the box; for the cloud providers, write a one-liner that hits any cheap endpoint with their key. The precheck hook is generic — you can validate config, environment, even a database connection before the user types anything (`#1132`).

### Typed `@Generable` args, live, on every provider

`TypedArgsLiveIntegrationTest` covers the full round-trip — `@Generable` schema generation → provider envelope (Ollama `parameters`, Anthropic `input_schema`, OpenAI `parameters`) → wire serialization → response parse → `KClass.constructFromMap` → typed executor — against real models. Three tests, one per provider, each gated so a fresh clone without keys stays green. Established that the typed `tool<Args, Result>` path is actually portable, not just "Ollama-shaped" (`#1675`).

### `apiKey` no longer leaks through `toString`

`ModelConfig` is a Kotlin `data class`, and the auto-generated `toString()` dumps every field — including the raw API key. One `log.info("config = $cfg")` away from a credential leak. `ModelConfig.toString()` is now overridden to mask: `apiKey=sk-ant…108chars` instead of the body. `equals`/`hashCode` still consider apiKey (cache keying stays correct); masking is observation-only. `SECURITY.md` gained a "Handling LLM provider credentials" section covering the `.secrets/` convention, file perms, the masking contract, and a "if a key is committed → rotate first" runbook (`#1665`).

## Fixed

### OllamaClient: assistant tool-call turns wire `content: null`

External bug report: every multi-turn agentic loop against Ollama Cloud `gpt-oss:120b-cloud` / `gpt-oss:20b-cloud` was hitting `500 Internal Server Error`. Root cause: assistant messages carrying `tool_calls` and no textual content were serialised as `content: ""`, but the OpenAI / Ollama chat-completions spec says `content` should be `null` (or omitted) when `tool_calls` is present, and the cloud's strict validator rejects the empty-string form. Local Ollama tolerated it, so the bug hid until cloud-routed deployments tried multi-turn.

The fix null-coerces only when **all three** hold: role is `assistant`, `tool_calls` is non-empty, and content is blank. Legitimate empty-string assistant turns (no tool_calls) keep their previous shape — different semantics, different wire bytes. The other two adapters were already spec-compliant; this is an Ollama-only patch. Six regression cases cover the truth table from the bug report, including the exact two-tool-call PlanMaster sequence the reporter attached (`#1694`).

## Binary compatibility

**Source-compatible** with 0.3.x — every new public API has defaults; existing code compiles unchanged.

**Wire-shape change for Ollama tool-call messages** (`#1694`) — assistant turns with `tool_calls` and no textual content now serialize as `content: null` on the wire instead of `content: ""`. Pure payload shaping; in-memory `LlmMessage` is unchanged. Local Ollama tolerated both shapes; Ollama Cloud's strict validators only accept the new form, so this is functionally a regression fix for cloud users.

## Migration

If you're on 0.3.x and only using Ollama, nothing to do. Bump the version, rebuild, ship.

If you want to try Claude or OpenAI, the recipe is one DSL line plus an API key:

```kotlin
agent("coder") {
    model {
        claude("claude-opus-4-7")            // or openai("gpt-4o")
        apiKey = System.getenv("ANTHROPIC_API_KEY")
        temperature = 0.0
        maxTokens = 4096                     // required for both cloud providers
    }
    skills { /* unchanged */ }
}
```

Local development convention: keep keys in `<repo-root>/.secrets/anthropic-key` and `<repo-root>/.secrets/openai-key` (gitignored), `chmod 0600`. See `SECURITY.md` for the full handling guidance.

## What's next (Phase 2)

- Streaming (`Flow<LlmResponseChunk>`) on every adapter — kills the dead-air spinner.
- Prompt caching headers for Claude — `cache_control: ephemeral` on long system prompts and knowledge blocks; ~90% cost cut on repeat turns.
- KSP compile-time `@Generable` (replaces runtime reflection).
- Google (Gemini) adapter — last on the multi-provider list.
- `Tool<IN, OUT>` base hierarchy + `McpTool<IN, OUT>` subclass, unblocking `grants { }` typed permissions.

Full roadmap: [`docs/roadmap.md`](docs/roadmap.md).
