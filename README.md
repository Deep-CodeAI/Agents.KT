<p align="center">
  <img src="branding/1_logo/PNG/transparent/logo_color_transparent.png" alt="Agents.KT" width="320" />
</p>

<p align="center">
  <strong>The auditable Kotlin agent runtime for regulated teams.</strong><br/>
  <em>Typed boundaries. Least-privilege tools. MCP-native.</em>
</p>

<p align="center">
  <a href="https://github.com/Deep-CodeAI/Agents.KT/actions/workflows/ci.yml"><img src="https://github.com/Deep-CodeAI/Agents.KT/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://central.sonatype.com/artifact/ai.deep-code/agents-kt"><img src="https://img.shields.io/maven-central/v/ai.deep-code/agents-kt?color=blue" alt="Maven Central" /></a>
  <a href="https://mvnrepository.com/artifact/ai.deep-code/agents-kt"><img src="https://badges.mvnrepository.com/badge/ai.deep-code/agents-kt/badge.svg?label=MvnRepository" alt="MvnRepository" /></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" /></a>
  <a href="https://openjdk.org"><img src="https://img.shields.io/badge/JDK-21+-orange" alt="JDK" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License: MIT" /></a>
</p>

---

Agents.KT is built for teams that need to know exactly what an AI system is allowed to do. Every agent is `Agent<IN, OUT>`: one input type, one output type, one job. Type mismatches and wrong compositions are caught by the compiler where composition is purely type-driven, and structural misuses fail fast at construction time.

The 0.6.0 line turns those boundaries into audit-ready evidence: deterministic permission manifests, runtime `manifestHash` correlation, JSONL audit export, OTel/LangSmith/Langfuse bridge adapters, before-interceptor policy hooks, and declarative tool policy metadata. Agents.KT is the runtime behind [agents-kt.dev](https://agents-kt.dev/).

---

## First 10 Minutes

**Requirements:** JDK 21+, Kotlin 2.x, Gradle

```kotlin
// build.gradle.kts
dependencies {
    implementation("ai.deep-code:agents-kt:0.6.1")
}
```

Or clone and build from source:

```bash
git clone https://github.com/Deep-CodeAI/Agents.KT.git
cd Agents.KT
./gradlew test
```

Then build one typed pipeline:

```kotlin
val parse = agent<RawText, Specification>("parse") {
    skills {
        skill<RawText, Specification>("parse-spec", "Splits raw text into a structured specification") {
            implementedBy { input -> Specification(input.text.split(",").map { it.trim() }) }
        }
    }
}
val generate = agent<Specification, CodeBundle>("generate") {
    skills {
        skill<Specification, CodeBundle>("gen-code", "Generates stub functions for each endpoint") {
            implementedBy { spec -> CodeBundle(spec.endpoints.joinToString("\n") { "fun $it() {}" }) }
        }
    }
}
val review = agent<CodeBundle, ReviewResult>("review") {
    skills {
        skill<CodeBundle, ReviewResult>("review-code", "Approves code if it is non-empty") {
            implementedBy { code -> ReviewResult(approved = code.source.isNotBlank()) }
        }
    }
}

// Compiler checks every boundary
val pipeline = parse then generate then review
// Pipeline<RawText, ReviewResult>

val result = pipeline(RawText("getUsers, createUser, deleteUser"))
// ReviewResult(approved=true)
```

Testing details — task names, integration test setup, mutation testing, and how to write tests with a stub `ModelClient` — are in [**`docs/testing.md`**](docs/testing.md). Build prerequisites are on the [**Building From Source**](https://github.com/Deep-CodeAI/Agents.KT/wiki/Building-From-Source) wiki page.

---

## What Agents.KT Owns

Agents.KT owns the runtime boundary model:

- Typed `Agent<IN, OUT>` contracts and composition operators.
- Per-skill tool authorization and typed tool handles.
- MCP client/server surfaces that share the same tool/skill shape.
- Permission manifests, declarative tool policies, and runtime audit correlation.
- JSONL audit export plus OTel, LangSmith, and Langfuse adapters through `ObservabilityBridge`.
- Local-first JVM execution with Ollama by default and cloud providers when you choose them.

These are the pieces the framework can make deterministic, testable, and reviewable in code. Start with [permission manifests](docs/permission-manifest.md), the [threat model](docs/threat-model.md), the [regulated deployment guide](docs/regulated-deployment.md), and the [comparison page](docs/comparison.md) for the release narrative.

## What Agents.KT Does Not Own

Agents.KT emits evidence and enforces in-runtime boundaries; it does not replace your deployment controls:

- It is not a legal compliance product. It produces compliance-supporting artifacts and audit-ready evidence; your counsel and compliance team still classify the use case.
- It does not sandbox arbitrary Kotlin lambdas in 0.6.0. `ToolPolicy` records intended filesystem/network/environment scope; OS/container enforcement remains a deployer responsibility until #1916.
- It does not rate-limit public MCP ingress. Use `McpServer` auth/policy plus your gateway.
- It does not ship a universal prompt-injection classifier. Wire your chosen detector through `onBeforeTurn`.
- It does not try to be a vector-store, eval-suite, or hosted orchestration platform. It is the typed JVM runtime boundary underneath those integrations.

---

## Why Agents.KT

Most agent frameworks let you wire anything to anything. Agents.KT says no.

| Problem | Agents.KT answer |
|---------|-----------------|
| God-agents with unlimited responsibilities | `Agent<IN, OUT>` — one type contract, compiler-enforced SRP |
| Runtime type mismatches between agents | `then` requires `A.OUT == B.IN` — compile error otherwise |
| The same agent instance wired into two places | Single-placement rule — `IllegalArgumentException` at construction time |
| LLM doesn't know which skill to use | Manual `skillSelection {}` routing or automatic LLM routing — descriptions sell each skill to the router |
| LLM doesn't know what context to load | `knowledge("key", "description") { }` entries — LLM reads descriptions before deciding to call |
| Flat pipelines only | Composition operators covering sequential, forum, parallel, iterative, and branching patterns |
| LLM output is an untyped string | `@Generable` + `@Guide` — JSON Schema, provider constrained decoding, prompt fragments, lenient deserializer, and `PartiallyGenerated<T>`; KSP-generated metadata avoids runtime reflection when present |
| MCP tools are wrappers, not first-class | `McpClient.tools()` returns first-class `McpTool<*, *>` handles, while `toolSkills()` keeps the prompt-style skill adapter; agents can also be exposed as MCP servers via `McpServer.from(agent)` |
| Permission model is stringly-typed | `grants { tools(writeFile, compile) }` — actual `Tool<*,*>` references, compiler-validated *(planned Phase 2)* |
| No testing story | AgentUnit — deterministic through semantic assertions *(planned)* |
| JVM frameworks require Java installed | Native CLI binary via GraalVM *(planned Phase 2 Priority)* |

---

## What's Shipped

This section is the index — every claim below points to working code in `main`, with the issue number that established it. Topical detail lives in [`docs/`](docs/).

### Implemented today

These APIs work in `main`, are unit-tested, and are exercised by integration tests (`./gradlew test` for default suite, `./gradlew integrationTest` for live-LLM):

- **Typed agents** — `Agent<IN, OUT>` with at least one skill producing `OUT`, validated at construction. See [docs/skills.md](docs/skills.md).
- **Skills with knowledge** — `skill { knowledge("key", "...") { } }`, lazy-loaded per call. See [docs/skills.md#shared-knowledge](docs/skills.md#shared-knowledge).
- **Agentic loop with tool calling** — multi-turn `chat ↔ tools` driven by the model. See [docs/model-and-tools.md](docs/model-and-tools.md).
- **Four model providers** — `model { ollama(...) }` for local/cloud Ollama, `model { claude("claude-opus-4-7"); apiKey = ... }` for Anthropic's Messages API, `model { openai("gpt-4o"); apiKey = ... }` for OpenAI Chat Completions, and `model { deepseek("deepseek-v4-flash"); apiKey = ... }` for DeepSeek's OpenAI-compatible API. All four go through one `ModelClient` interface — `LlmMessage` / `LlmResponse` are provider-agnostic, tools/system/role mapping is per-adapter (#1644, #1656).
- **Typed tools via `@Generable`** — `tool<Args, Result>(...)` with reflection-built JSON Schema; `additionalProperties: false`; sealed-discriminator validation (#658, #661, #699).
- **Provider-neutral tool handles** — local typed tool handles and MCP-discovered tools share `Tool<IN, OUT>`; `McpClient.tools()` returns `McpTool<Map<String, Any?>, String>` for grants/manifests/policy work while `toolSkills()` remains available for primary-skill use (#1948).
- **Provider constrained decoding for `@Generable` outputs** — agentic skills returning `@Generable` types pass their JSON Schema to supporting providers automatically: OpenAI `response_format.json_schema`, Ollama `format`, and Anthropic's forced structured-output tool pattern (#1949).
- **Reasoning/thinking stream** — opt-in `model { reasoning(budgetTokens = ..., effort = ...) }` surfaces a model's reasoning as `AgentEvent.Reasoning`, a channel separate from the answer `Token` stream, so a production UI can render live reasoning instead of a spinner. Claude (extended thinking), DeepSeek (`reasoning_content`), and Ollama (`thinking`) emit reasoning text; OpenAI Chat Completions reports `reasoning_effort` + reasoning token counts only (no text). Off by default (#2406). See [docs/streaming.md](docs/streaming.md).
- **Typed tool refs in skill allowlists** — `tool(...)` returns a `Tool<Args, Result>` handle; `skill { tools(writeFile, compile) }` accepts handles, the IDE catches typos (#1015–#1017). The legacy `tools("name")` string form remains for built-in tools and runtime-discovered MCP names but produces a deprecation warning.
- **Declarative tool policies** — `tool { policy { risk = ToolRisk.Medium; filesystem { read("/uploads/**") }; network { denyAll() } } }` records expected filesystem/network/environment scope for manifests and audit events. Declarative only in 0.6.0; sandbox enforcement is separate (#1915, #1916).
- **Permission manifests** — `agent.permissionManifest()` and `pipeline.permissionManifest()` emit deterministic JSON/YAML capability graphs with agents, skills, tools, memory, MCP, providers, budgets, guardrails, composition structure, masked secrets, and a SHA-256 hash that is attached to runtime events (#1912). See [docs/permission-manifest.md](docs/permission-manifest.md).
- **Per-skill tool authorization** — runtime allowlist; the prompt's "Available tools" listing is descriptive, the security boundary is the runtime check (#630). See [docs/model-and-tools.md#tool-authorization-model](docs/model-and-tools.md#tool-authorization-model).
- **Before interceptors** — `onBeforeSkill`, `onBeforeTurn`, and `onBeforeToolCall` return `Decision` (`Proceed`, `ProceedWith`, `Deny`, `Substitute`) for dynamic policy, prompt filtering, argument mutation, and synthetic results (#1907). See [docs/interceptors.md](docs/interceptors.md).
- **Inline tool-call fallback** — auto-recovery when an Ollama model rejects native `tools` (e.g. `gemma3:4b`) — strips the field, injects inline JSON format prompt, retries (#702, #706). See [docs/model-and-tools.md#inline-tool-call-fallback-ollama-models-without-native-tool-support](docs/model-and-tools.md#inline-tool-call-fallback-ollama-models-without-native-tool-support).
- **Composition operators** — `then`, `/` (parallel), `*` and `forum { }` (multi-agent), `.loop {}`, `.branch {}` on sealed types. See [docs/composition.md](docs/composition.md).
- **Single-placement rule** — each `Agent` instance participates in at most one structure; second placement throws at construction. See [docs/composition.md#single-placement-rule](docs/composition.md#single-placement-rule).
- **Memory bank** — `memory(MemoryBank())` auto-injects `memory_read` / `memory_write` / `memory_search` tools. See [docs/memory.md](docs/memory.md).
- **LLM skill routing** — manual `skillSelection { }` or LLM router with `skillSelectionConfidenceThreshold`; `SkillRoute(name, confidence, rationale)` is structured (#641). See [docs/model-and-tools.md#skill-selection](docs/model-and-tools.md#skill-selection).
- **Tool error recovery** — per-tool `onError`, per-skill default, agent default; built-in `escalate` and `throwException` agents. See [docs/error-recovery.md](docs/error-recovery.md).
- **Budget controls** — `budget { maxTurns; maxToolCalls; maxDuration; perToolTimeout; maxTokens; maxConsecutiveSameTool }` (`perToolTimeout` covers regular and session-aware tools; token counts cumulative across turns when the provider reports usage; `maxConsecutiveSameTool` catches LLM retry loops on a broken tool) (#637, #963, #969, #1903). `onBudgetExceeded { reason, currentLimit -> BudgetDecision.Extend(newLimit) }` raises a cap and continues instead of throwing — a long-running agent can grant itself more tool calls mid-run rather than failing (#2412).
- **JSONL audit exporter** — `:agents-kt-observability` writes append-only, one-line-per-event audit rows with `requestId`, `sessionId`, `manifestHash`, agent/skill/tool ids, event type, provider, and model; raw arguments/results are omitted by default (#1914). See [docs/observability.md](docs/observability.md).
- **ObservabilityBridge adapters** — `.observe(OtelBridge(tracer))` maps runtime events to OTel spans (#1908), `.observe(LangSmithBridge(apiKey, project))` maps the same events to LangSmith run trees (#1909), and `.observe(LangfuseBridge(publicKey, secretKey))` maps them to Langfuse traces, generations, spans, and events (#1910), while keeping core vendor-free. See [docs/observability.md](docs/observability.md).
- **MCP client** — `mcp { server() }` over HTTP / stdio / TCP; Bearer auth; namespaced tools (`server.tool`). See [docs/mcp.md](docs/mcp.md).
- **MCP server** — `McpServer.from(agent)` exposes an agent as an MCP-conformant HTTP server with explicit `tools/listChanged: false` capability (#619), inbound bearer auth, Host/Origin allowlists, and per-principal tool policy (#1902); `McpStdioServer.from(agent)` serves the same tools/prompts/resources over line-delimited stdio (#2045).
- **`McpRunner` standalone** — picocli-style one-liner main for shipping agents as MCP services over HTTP or `--stdio`.
- **`LiveShow` / `LiveRunner`** — REPL deployment with string-concatenated conversation history. Six factory overloads (Agent, Pipeline, Forum, Parallel, Loop, Branch) for any String-input structure; `--once "<prompt>"` for non-interactive use; built-in `/quit`, `/clear`, `/help` slash commands; user-extensible; JLine-backed cursor movement and in-memory arrow-key history for interactive terminals (#981, #985).
- **`Swarm` + `absorb`** — drop sibling agent JARs into a folder, the captain ServiceLoader-discovers them and absorbs each as a tool with full agent personality preserved (prompt, skills, knowledge, memory). In-JVM, no IPC, no static-typing-across-JARs limitation MCP-stdio would impose (#984).
- **Frozen-after-construction agents** — structural mutators (skills, tools, memory, model, budget, prompt, error handlers, routing) reject post-construction calls (#697, #708).
- **Encapsulated tool/skill maps** — `Agent.toolMap` and `Agent.skills` are read-only `Map` views; mutation only via DSL or framework-internal escape hatches (#659, #667).
- **`LlmProviderException`** — provider-boundary errors (auth, model-not-found, capability mismatch) surface distinctly from output-parse errors (#702).
- **Untrusted tool-output wrapping** — tool results carry an envelope so the model can't impersonate framework messages (#642).
- **`loadResource(path)`** — read agent system prompts (or any other context) from `src/main/resources/...` instead of inline string literals; fail-fast at construction if the path is wrong. `loadResourceOrNull` for the optional case (#980).

### Experimental

APIs that exist in `main` and have tests, but haven't been exercised in production and may evolve based on real-world usage:

- **Forum with `transcriptCaptain`** — captain receives the full `ForumTranscript<IN>` (all participant outputs) instead of only the original input (#639). Useful for synthesis patterns; semantics may sharpen with usage.
- **Branch on sealed hierarchies** — `BranchRoute` sealed type with `onNull` / `onElse` markers and construction-time completeness validation (#640). Stable surface, limited real-world coverage.

## What's Not Shipped

The release is intentionally explicit about what the framework does not enforce yet.

### Security Model

What the framework enforces today:

| Boundary | Enforcement | Established by |
|----------|-------------|----------------|
| Tool authorization | Runtime per-skill allowlist; unknown calls rejected — prompt is descriptive only | #630 |
| Tool policy declarations | `ToolPolicy` captures declared risk and filesystem/network/environment scope for review and audit | #1915 |
| Dynamic policy | `onBefore*` interceptors can deny, mutate, or substitute before skills, turns, and allowed tool calls run | #1907 |
| Tool name typos | Fail-fast at agent construction | #631 |
| Reserved memory names | `memory_read` / `memory_write` / `memory_search` cannot be shadowed by user tools | #659 |
| Agent contract | Skills, tools, memory, model, budget, prompt frozen after `agent { }` returns | #697, #708 |
| Typed args | `additionalProperties: false`; sealed `type` discriminator must match constructed variant | #661, #699 |
| Repaired args | Re-validated through the typed schema before reaching the executor | #658 |
| Tool output trust | Tool results wrapped in untrusted envelope so the model can't forge framework messages | #642 |
| Provider errors | Surface as `LlmProviderException` — never confused with model output | #702 |
| Budget caps | `maxTurns`, `maxToolCalls`, `maxDuration`, `perToolTimeout`, `maxTokens`, `maxConsecutiveSameTool` (`perToolTimeout` covers regular tools via worker interrupt and session-aware tools via coroutine cancellation; token cap cumulative across turns when provider reports usage; `maxConsecutiveSameTool` catches retry loops on a broken tool) | #637, #963, #969, #1903 |

What the framework does **not** enforce — your responsibility:

- **Built-in prompt-injection classifier** — wire your chosen classifier through `onBeforeTurn`; the framework provides the hook, not the detector.
- **Sandboxing of tool executors** — tool code runs in-process with full JVM permissions. `ToolPolicy` declares intended scope for review/audit, but sandbox at the OS / container layer if the tools execute untrusted plans.
- **Resource limits beyond budgets** — no automatic memory, file-descriptor, or network quotas.
- **MCP request rate limits** — `McpServer` authenticates and filters tools, but per-client throttling still belongs in your gateway for now.

### Known Limitations

- **Four LLM providers shipped** — Ollama, Anthropic, OpenAI, and DeepSeek. Google (Gemini) adapter is Phase 2; the injectable `ModelClient` covers test stubs and your own adapters in the meantime.
- **Synchronous agentic loop** — `runBlocking` inside the loop until the suspend refactor lands (#638). Calling agents from existing coroutine scopes works but doesn't propagate cancellation cleanly.
- **No built-in MCP rate limiter** — use `McpServer` auth/policy plus a gateway for throttling. Agent/runtime audit events have a first-party JSONL exporter in `:agents-kt-observability`.
- **Streaming runtime** *(shipped — v0.5.0)*. `agent.session(input): AgentSession<OUT>` exposes `events: Flow<AgentEvent<OUT>>` — bracket events (`SkillStarted` / `SkillCompleted` / `Completed<OUT>` / `Failed`) plus mid-loop `Token` / `Reasoning` / `ToolCallStarted` / `ToolCallArgumentsDelta` / `ToolCallFinished` events as the agentic loop runs. All events carry `requestId`, `sessionId`, and `manifestHash` for audit correlation (#1913). Ollama, Anthropic, OpenAI, and DeepSeek stream at the wire (DeepSeek via the OpenAI-compatible SSE path); live integration tests measure 19 / 2 / 19 chunks for the original three native adapters. `SkillCompleted.tokensUsed` and `Completed.tokensUsed` carry cumulative `TokenUsage` across all turns. The underlying `LlmChunk` sealed type + `ModelClient.chatStream(messages): Flow<LlmChunk>` foundation (#1722) is what custom adapters plug into. See [docs/streaming.md](docs/streaming.md) for the full API + the [v0.5.0 streaming premortem](docs/premortem-0.5.0-streaming.md) for design rationale.
  - *Partial cancellation today.* `Flow` collection cancels promptly, and `perToolTimeout` now applies to both regular and session-aware tool calls. Synchronous skill bodies and blocking HTTP reads still are not fully coroutine-cancellable mid-call; the remaining adapter migration is the `sendAsync`/suspend-refactor track.
  - *Leaf-agent sessions only.* Composition operators (`Pipeline` / `Branch` / `wrap` / `Swarm`) don't yet flow inner events through their own `session(...)` surfaces — known gap, see #1745 follow-ups.
- **No native binary** — JVM-only (≥ JDK 21). GraalVM and `jlink` bundles are Phase 2 priorities.
- **No A2A protocol yet** — agent-to-agent over network (Phase 2 / 3).
- **Inline-tool-call fallback model variance** — small Ollama models (e.g. `gemma3:4b`) reliably emit single tool calls via the inline format but may produce thin final-turn text after multi-step tool sequences. For multi-step reasoning, a tool-native model (`gpt-oss:20b-cloud` and similar) is the better fit.
- **No tool sandboxing** — tool executors run in-process with full JVM privileges. `grants { }` controls *which* tools an agent can call, not *what they can do* once invoked. Sandboxed execution (`ProcessSandbox` / `WasmSandbox` / `DockerSandbox` opt-in backends) is on the Phase 3 roadmap.
- **Text-only I/O today** — `LlmMessage.content: String` carries text. Image input (vision-capable adapters: Anthropic, OpenAI, Ollama, Gemini) and audio input land in Phase 2 alongside an `LlmContent` sealed-block evolution of the message model. Image generation (`ImageModelClient`: DALL-E, Imagen, Stability) and text-to-speech (`TTSModelClient`: OpenAI TTS, ElevenLabs, Google) are Phase 3.

For planned features beyond these limitations, see [docs/roadmap.md](docs/roadmap.md).

---

## Roadmap (highlights)

**Phase 1 — Core DSL** *(in progress)*: typed agents, skills, knowledge, composition operators (`then`, `/`, `*`, `forum`, `.loop`, `.branch`), MCP client + server, agent memory, `loadResource(path)` for prompts from classpath, agentic loop with full budget controls (`maxTurns` / `maxToolCalls` / `maxDuration` / `perToolTimeout` / `maxTokens` / `maxConsecutiveSameTool`), observability hooks (`onSkillChosen`, `onToolUse`, `onKnowledgeUsed`, `onError`, `onBudgetThreshold`, `onBudgetExceeded`, `Agent.observe { }`), runtime audit context (`requestId`, `sessionId`, `manifestHash`), JSONL audit export, declarative tool policy metadata, and before-interceptor policy hooks (`onBeforeSkill`, `onBeforeTurn`, `onBeforeToolCall`).

**Phase 2 — Runtime + Distribution** *(Q2 2026)*: remaining provider (Google), native CLI / jlink, `grants {}` permissions, session model, Flow-based observability, **multimodal input** (image + audio content blocks; vision-capable adapters for Anthropic/OpenAI/Ollama/Gemini), `agent.json` serialization, Gradle plugin. *(Anthropic + OpenAI adapters landed in #1644 / #1656; KSP `@Generable` codegen shipped in v0.4.6; per-adapter native streaming overrides — Anthropic SSE, OpenAI SSE, Ollama NDJSON — shipped in v0.5.0; provider-level constrained decoding for `@Generable` outputs shipped in v0.6.0 via #1949; the provider-neutral `Tool<IN, OUT>` / `McpTool<IN, OUT>` hierarchy shipped in v0.6.0 via #1948.)*

**Phase 3 — Production** *(Q3 2026)*: Layer 2 Structure DSL, all 37 compile-time validations, AgentUnit, A2A protocol, file-based knowledge with RAG, OpenTelemetry, **sandboxed tool execution** (`SandboxedExecutor` with `ProcessSandbox` (Seatbelt / bwrap), `WasmSandbox` (Chicory), `DockerSandbox` backends — opt-in per tool, subprocess-shaped tools only, default executor stays in-process), **generative outputs** (`ImageModelClient` for DALL-E / Imagen / Stability, `TTSModelClient` for OpenAI / ElevenLabs / Google).

**Phase 4 — Ecosystem** *(Q4 2026)*: knowledge packs, NL → DSL generation, Skillify, visual editor, knowledge marketplace.

Full per-feature breakdown in [**docs/roadmap.md**](docs/roadmap.md).

---

## Documentation

Topical guides:

- [**Website**](https://agents-kt.dev/) — distilled product tour: typed contracts, constrained tools, local-first runtime, swarm, install, and docs.
- [**Wiki**](https://github.com/Deep-CodeAI/Agents.KT/wiki) — expanded learning path and operational guides.
- [**Skills**](docs/skills.md) — agent skills, knowledge entries, shared catalogs, the lazy-vs-eager context model.
- [**Model & Tool Calling**](docs/model-and-tools.md) — agentic loop, typed tools via `@Generable`, inline-tool fallback, authorization, skill selection, budget caps.
- [**MCP Integration**](docs/mcp.md) — `mcp { server() }` client, `McpServer.from(agent)`, `McpRunner` standalone.
- [**MCP Server Hardening**](docs/mcp-server.md) — inbound auth, Host/Origin allowlists, per-client tool policy, and gateway deployment recipes.
- [**Tool Error Recovery**](docs/error-recovery.md) — `onError { invalidArgs / deserializationError / executionError }`, `RepairResult.Fixed/Retry/Escalated/Unrecoverable`, default vs per-tool handlers.
- [**Agent Memory**](docs/memory.md) — `memory(MemoryBank())`, the three auto-injected tools, sharing memory across agents.
- [**Guided Generation**](docs/generation.md) — `@Generable`, `@Guide`, `@LlmDescription`, JSON-Schema generation, lenient deserializer, `PartiallyGenerated<T>`.
- [**Composition Operators**](docs/composition.md) — `then`, `/`, `*`, `forum`, `.loop {}`, `.branch {}`, single-placement rule, type algebra.
- [**InternalsAgent**](docs/internals-agent.md) — query agents-kt internals from your IDE via MCP (Cursor / Claude Desktop).
- [**Threat Model**](docs/threat-model.md) — five deployment scenarios + anti-patterns; self-classify your use case in 5 min.
- [**Production Hardening**](docs/production-hardening.md) — actionable checklist for "before going live."
- [**Regulated Deployment**](docs/regulated-deployment.md) — capability inventory, action log, decision points; EU AI Act mapping.
- [**Observability**](docs/observability.md) — JSONL audit exporter, `ObservabilityBridge`, OTel, LangSmith, and Langfuse adapters.
- [**Permission Manifest**](docs/permission-manifest.md) — deterministic capability graph, CI verification, and runtime `manifestHash` correlation.
- [**Comparison**](docs/comparison.md) — Agents.KT vs LangChain / Semantic Kernel / AutoGen / raw MCP.
- [**Interceptors**](docs/interceptors.md) — `onBefore*` family + `Decision` sealed type for deny/mutate/substitute policy (#1907).
- [**Roadmap**](docs/roadmap.md) — full Phase 1–4 feature plan.

---

## Current Release

`main` is currently `0.6.1` — a feature patch on the 0.6.0 audit platform, adding a **reasoning/thinking stream** (`AgentEvent.Reasoning`) across all four providers (#2406), **`onBudgetExceeded`** to raise a budget cap and continue mid-run (#2412), first-class **`onToolDenied` / `PipelineEvent.ToolDenied`** observability (#2395), **typed parameter schemas** for the built-in tools (#2379), and an experimental **snapshot/resume** foundation (#2416). It builds on the 0.6.0 line — an additive telemetry release on top of the v0.5.0 platform. **Permission manifest**: `:agents-kt-manifest` emits deterministic JSON/YAML capability graphs for agents and compositions, masks provider secrets, verifies high-risk widening in CI, and attaches the manifest SHA-256 to runtime audit context. **Token usage telemetry**: `onTokenUsage { usage -> }` exposes provider-reported `TokenUsage(promptTokens, completionTokens, cachedInputTokens, provider, model)` once per successful LLM round-trip, including end-of-stream usage for streaming adapters. **JSONL audit export**: `:agents-kt-observability` writes canonical append-only audit rows for `PipelineEvent` and `AgentEvent` with request/session/manifest correlation and PII-safe default field selection. **Observability bridge**: `:agents-kt-observability` exposes `ObservabilityBridge` and `.observe(bridge)`, while `:agents-kt-otel` maps runtime events and before-interceptor decisions to OpenTelemetry spans, `:agents-kt-langsmith` maps the same events to LangSmith run trees, and `:agents-kt-langfuse` maps them to Langfuse traces/generations/spans/events without adding any vendor to the core classpath. **DeepSeek provider**: `model { deepseek(...) }` joins Ollama, Anthropic, and OpenAI as the fourth built-in `ModelClient`. **Declarative tool policy**: `ToolPolicy` records tool risk plus filesystem/network/environment declarations for manifest/audit consumers; enforcement remains #1916. **Provider constrained decoding**: agentic skills returning `@Generable` types now pass JSON Schema to supporting providers automatically (OpenAI `response_format.json_schema`, Ollama `format`, Anthropic structured-output tool), with parser retries still retained as defense-in-depth. **Streaming runtime**: `agent.session(input).events: Flow<AgentEvent<OUT>>` surfaces typed `Token` / `ToolCall*` / bracket events as the agentic loop runs, with `requestId`, `sessionId`, and `manifestHash` on every event. Ollama, Anthropic, OpenAI, and DeepSeek stream at the wire (DeepSeek through the OpenAI-compatible SSE path). Every composition operator (`then` / `wrap` / `Branch` / `Loop` / `Parallel` / `Forum` / `Swarm`) surfaces sessions with `agentId`-tagged inner events. **MCP-as-skills unification**: `mcp.toolSkills()` + `mcp.promptSkills()` + `mcp.resourceSkills()` — every MCP capability shape exposes as a `Skill` consumable in `skills { +... }`. `McpServer` gains DSLs to register prompts and resources alongside agents-as-tools, inbound bearer auth, Host/Origin allowlists, per-principal tool policy, plus `McpStdioServer` and `McpRunner --stdio` expose the same server-side capability set over line-delimited stdio. `McpServerInfo` snapshots the full capability matrix. The 0.4 line (kotlin-reflect compileOnly, KSP @Generable, BouncyCastle hardening, wrap operator, original three providers) is included.

Use Maven Central for published artifacts and tags for immutable release points.

## License

[MIT](LICENSE) — Deep-Code.AI
