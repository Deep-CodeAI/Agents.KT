# Agents.KT v0.8.0 — Interoperable, multimodal agents, with capability grants

**Release date:** 2026-06-14

0.8.0 is the largest minor since 0.5.0. The boundary-first runtime grows outward: it now **talks to
other agents**, **sees and hears**, **composes in richer shapes**, and lets you **grant capabilities
explicitly** — while keeping the typed `Agent<IN, OUT>` contract and the audit/manifest spine intact.
Additive throughout: existing public API surfaces are preserved (drop-in on the 0.7.x line).

## Headlines

- **A2A v1 — agent-to-agent interop (#3864).** Agents.KT agents are A2A servers (typed skills exposed
  via AgentCard) and typed A2A clients — cross-system discovery and invocation over the wire.
- **Multimodal, end to end.** Vision input across Claude / OpenAI / Ollama (#2466–#2470); audio as
  tools — `transcribe_audio` / `speak` with self-hosted Whisper / Qwen adapters (#4501), an in-process
  `:agents-kt-whisper-jni` STT module (#4505), and image generation + TTS (#3867). Weights never ship
  in the jar.
- **Eighth model provider: Google Gemini (#1917).** A full from-scratch adapter (Gemini is not
  OpenAI-compatible): `contents`/`parts`, `functionDeclarations` tool calling, native SSE streaming,
  `responseJsonSchema` constrained decoding, thought-summary reasoning, `inlineData` vision. Joins
  Ollama / Anthropic / OpenAI / DeepSeek / Kimi / OpenRouter / Perplexity.
- **Capability grants (#4545).** `grants { allow(writeFile); confirm(deploy) }` — `allow` tools are
  freely callable; `confirm` tools require the **granting agent's** authorization (fail-closed), not a
  human gate. Build-validated; opt-in.
- **Richer composition.** `handoff` (#3871), `firstOf` / `.speculative(n)` (#3869), `loopUntil` +
  `evalGate` (#3870), built-in aggregators on `/` (#3872), and built-in forum captains (#3877).
- **RAG seam (#3863)** — `EmbeddingStore` SPI + query-aware knowledge, with LangChain4j / Spring-AI
  adapter modules.
- **Human-in-the-loop + eval.** `HumanGateRegistry` (#3868); a typed eval harness with
  LLM-as-judge and cross-model regression (#3876).
- **agent.json (#4516)** — deterministic, byte-stable serialization of an agent's definition
  (distinct from the permission manifest and the A2A AgentCard).
- **Agentic-web standards groundwork** — PRD §12.6–§12.9 plan AGNTCY (OASF/DIR/Identity), AG-UI,
  x402 payments, and NLWeb, positioned against the runtime.

Plus history compression (#3865), pipeline stage events (#4491), compaction strategies (#4492),
typed tool hooks (#4493), memory retention strategies (#4515), W3C trace propagation across MCP/A2A
(#3873), and an antifragility hardening pass (#4495–#4500).

See [`CHANGELOG.md`](CHANGELOG.md) for the full, itemized list.

## Deferred to 0.9.0

The Layer-2 **sandbox backends** originally pencilled for 0.8 slipped — they want a Linux-capable
environment to build and verify:

- `DockerSandbox` (#2895), the network hostname-allowlist **egress proxy** (#2893), and
  **read confinement** (#4546) move to **0.9.0**.
- `WasmSandbox` (#2894) was closed **won't-do** — embedding a WASM runtime to sandbox tools isn't
  rational (`ProcessSandbox` already covers it). The rational WASM direction — compiling **agents** to
  WASM for portable execution — is a separate forward-looking track (#4547, starting with a
  feasibility spike).

## Compatibility

Additive, no breaking changes to existing public API. The capability-grants block, A2A surfaces,
multimodal tools, and the Gemini provider are all opt-in. CodeQL's `java-kotlin` check is red on the
Kotlin 2.4 toolchain (upstream codeql#21938) — the Gradle build is the gate.
