<p align="center">
  <img src="branding/1_logo/PNG/transparent/logo_color_transparent.png" alt="Agents.KT" width="320" />
</p>

<p align="center">
  <strong>AI agents with boundaries. Through typed Kotlin.</strong><br/>
  <em>One input. One output. Allowed tools only.</em>
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

Every agent is `Agent<IN, OUT>`. One input type, one output type, one job. Type mismatches and wrong compositions are caught by the compiler where composition is purely type-driven, and structural misuses fail fast at construction time. Reused agent instances are caught at construction time.

Agents.KT is the runtime behind [agents-kt.dev](https://agents-kt.dev/): a local-first Kotlin/JVM framework for typed agent pipelines, explicit per-skill tool authorization, MCP integration, memory, budgets, observability hooks, and swarm-style agent delegation when a single agent stops being the right shape.

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

---

## Product Shape

The public site is the short version of the runtime contract:

| Site scene | Runtime surface |
|------------|-----------------|
| **Typed by design** | `Agent<IN, OUT>` values compose like functions with `then`, `/`, `forum`, loops, and sealed branches. |
| **Tools with limits** | Tools are registered on the agent but authorized per skill; typed tool handles catch allowlist mistakes early. |
| **Local first** | Start with Ollama on the JVM, then add MCP when an agent needs external tools or should become an MCP endpoint. |
| **Swarm when needed** | Drop sibling agent JARs onto the classpath; a captain discovers and absorbs them as delegated tools. |
| **Start with one dependency** | Pin the Maven artifact, build one typed agent, then add memory, budgets, and observability as the workflow asks for them. |
| **Docs for the full system** | The wiki and `docs/` cover first agents, composition, tools, MCP, memory, budgets, observability, and swarm. |

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
| MCP tools are wrappers, not first-class | `mcp { server() }` agent DSL — three transports (HTTP/stdio/TCP), auth, namespacing; agents can also be exposed as MCP servers via `McpServer.from(agent)` |
| Permission model is stringly-typed | `grants { tools(writeFile, compile) }` — actual `Tool<*,*>` references, compiler-validated *(planned Phase 2)* |
| No testing story | AgentUnit — deterministic through semantic assertions *(planned)* |
| JVM frameworks require Java installed | Native CLI binary via GraalVM *(planned Phase 2 Priority)* |

---

## What's in the Box

This section is the index — every claim below points to working code in `main`, with the issue number that established it. Topical detail lives in [`docs/`](docs/).

### Implemented today

These APIs work in `main`, are unit-tested, and are exercised by integration tests (`./gradlew test` for default suite, `./gradlew integrationTest` for live-LLM):

- **Typed agents** — `Agent<IN, OUT>` with at least one skill producing `OUT`, validated at construction. See [docs/skills.md](docs/skills.md).
- **Skills with knowledge** — `skill { knowledge("key", "...") { } }`, lazy-loaded per call. See [docs/skills.md#shared-knowledge](docs/skills.md#shared-knowledge).
- **Agentic loop with tool calling** — multi-turn `chat ↔ tools` driven by the model. See [docs/model-and-tools.md](docs/model-and-tools.md).
- **Three model providers** — `model { ollama(...) }` for local/cloud Ollama, `model { claude("claude-opus-4-7"); apiKey = ... }` for Anthropic's Messages API, and `model { openai("gpt-4o"); apiKey = ... }` for OpenAI Chat Completions. All three go through one `ModelClient` interface — `LlmMessage` / `LlmResponse` are provider-agnostic, tools/system/role mapping is per-adapter (#1644, #1656).
- **Typed tools via `@Generable`** — `tool<Args, Result>(...)` with reflection-built JSON Schema; `additionalProperties: false`; sealed-discriminator validation (#658, #661, #699).
- **Provider constrained decoding for `@Generable` outputs** — agentic skills returning `@Generable` types pass their JSON Schema to supporting providers automatically: OpenAI `response_format.json_schema`, Ollama `format`, and Anthropic's forced structured-output tool pattern (#1949).
- **Typed tool refs in skill allowlists** — `tool(...)` returns a `Tool<Args, Result>` handle; `skill { tools(writeFile, compile) }` accepts handles, the IDE catches typos (#1015–#1017). The legacy `tools("name")` string form remains for built-in tools and runtime-discovered MCP names but produces a deprecation warning.
- **Per-skill tool authorization** — runtime allowlist; the prompt's "Available tools" listing is descriptive, the security boundary is the runtime check (#630). See [docs/model-and-tools.md#tool-authorization-model](docs/model-and-tools.md#tool-authorization-model).
- **Inline tool-call fallback** — auto-recovery when an Ollama model rejects native `tools` (e.g. `gemma3:4b`) — strips the field, injects inline JSON format prompt, retries (#702, #706). See [docs/model-and-tools.md#inline-tool-call-fallback-ollama-models-without-native-tool-support](docs/model-and-tools.md#inline-tool-call-fallback-ollama-models-without-native-tool-support).
- **Composition operators** — `then`, `/` (parallel), `*` and `forum { }` (multi-agent), `.loop {}`, `.branch {}` on sealed types. See [docs/composition.md](docs/composition.md).
- **Single-placement rule** — each `Agent` instance participates in at most one structure; second placement throws at construction. See [docs/composition.md#single-placement-rule](docs/composition.md#single-placement-rule).
- **Memory bank** — `memory(MemoryBank())` auto-injects `memory_read` / `memory_write` / `memory_search` tools. See [docs/memory.md](docs/memory.md).
- **LLM skill routing** — manual `skillSelection { }` or LLM router with `skillSelectionConfidenceThreshold`; `SkillRoute(name, confidence, rationale)` is structured (#641). See [docs/model-and-tools.md#skill-selection](docs/model-and-tools.md#skill-selection).
- **Tool error recovery** — per-tool `onError`, per-skill default, agent default; built-in `escalate` and `throwException` agents. See [docs/error-recovery.md](docs/error-recovery.md).
- **Budget controls** — `budget { maxTurns; maxToolCalls; maxDuration; perToolTimeout; maxTokens; maxConsecutiveSameTool }` (sacrificial-thread enforcement; token counts cumulative across turns when the provider reports usage; `maxConsecutiveSameTool` catches LLM retry loops on a broken tool) (#637, #963, #969).
- **MCP client** — `mcp { server() }` over HTTP / stdio / TCP; Bearer auth; namespaced tools (`server.tool`). See [docs/mcp.md](docs/mcp.md).
- **MCP server** — `McpServer.from(agent)` exposes an agent as an MCP-conformant HTTP server with explicit `tools/listChanged: false` capability (#619); `McpStdioServer.from(agent)` serves the same tools/prompts/resources over line-delimited stdio (#2045).
- **`McpRunner` standalone** — picocli-style one-liner main for shipping agents as MCP services over HTTP or `--stdio`.
- **`LiveShow` / `LiveRunner`** — REPL deployment with string-concatenated conversation history. Six factory overloads (Agent, Pipeline, Forum, Parallel, Loop, Branch) for any String-input structure; `--once "<prompt>"` for non-interactive use; built-in `/quit`, `/clear`, `/help` slash commands; user-extensible (#981).
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

### Security model

What the framework enforces today:

| Boundary | Enforcement | Established by |
|----------|-------------|----------------|
| Tool authorization | Runtime per-skill allowlist; unknown calls rejected — prompt is descriptive only | #630 |
| Tool name typos | Fail-fast at agent construction | #631 |
| Reserved memory names | `memory_read` / `memory_write` / `memory_search` cannot be shadowed by user tools | #659 |
| Agent contract | Skills, tools, memory, model, budget, prompt frozen after `agent { }` returns | #697, #708 |
| Typed args | `additionalProperties: false`; sealed `type` discriminator must match constructed variant | #661, #699 |
| Repaired args | Re-validated through the typed schema before reaching the executor | #658 |
| Tool output trust | Tool results wrapped in untrusted envelope so the model can't forge framework messages | #642 |
| Provider errors | Surface as `LlmProviderException` — never confused with model output | #702 |
| Budget caps | `maxTurns`, `maxToolCalls`, `maxDuration`, `perToolTimeout`, `maxTokens`, `maxConsecutiveSameTool` (sacrificial-thread enforced; token cap cumulative across turns when provider reports usage; `maxConsecutiveSameTool` catches retry loops on a broken tool) | #637, #963, #969 |

What the framework does **not** enforce — your responsibility:

- **Prompt-injection content filtering** — assumes you trust your inputs and system prompts.
- **Sandboxing of tool executors** — tool code runs in-process with full JVM permissions; sandbox at the OS / container layer if the tools execute untrusted plans.
- **Resource limits beyond budgets** — no automatic memory, file-descriptor, or network quotas.
- **Authentication on `McpServer`** — incoming MCP requests are not credential-checked yet (see Known Limitations).

### Known limitations

- **Three LLM providers shipped** — Ollama, Anthropic, and OpenAI. Google (Gemini) adapter is Phase 2; the injectable `ModelClient` covers test stubs and your own adapters in the meantime.
- **Synchronous agentic loop** — `runBlocking` inside the loop until the suspend refactor lands (#638). Calling agents from existing coroutine scopes works but doesn't propagate cancellation cleanly.
- **No incoming auth on `McpServer`** — outgoing client supports Bearer; the server does not validate credentials. Suitable for trusted-network deployments only.
- **No Origin header validation on MCP HTTP** — deferred until the MCP-server hardening pass.
- **Streaming runtime** *(shipped — v0.5.0)*. `agent.session(input): AgentSession<OUT>` exposes `events: Flow<AgentEvent<OUT>>` — bracket events (`SkillStarted` / `SkillCompleted` / `Completed<OUT>` / `Failed`) plus mid-loop `Token` / `ToolCallStarted` / `ToolCallArgumentsDelta` / `ToolCallFinished` events as the agentic loop runs. All three adapters stream natively at the wire (Ollama NDJSON, Anthropic SSE, OpenAI SSE); live integration tests measure 19 / 2 / 19 chunks per response respectively. `SkillCompleted.tokensUsed` and `Completed.tokensUsed` carry cumulative `TokenUsage` across all turns. The underlying `LlmChunk` sealed type + `ModelClient.chatStream(messages): Flow<LlmChunk>` foundation (#1722) is what custom adapters plug into. See [docs/streaming.md](docs/streaming.md) for the full API + the [v0.5.0 streaming premortem](docs/premortem-0.5.0-streaming.md) for design rationale.
  - *Partial cancellation today.* `Flow` collection cancels promptly; synchronous skill bodies and blocking HTTP reads aren't coroutine-cancellable mid-call. The `sendAsync` adapter migration that closes this gap is tracked under [#1903](../../issues/1903).
  - *Leaf-agent sessions only.* Composition operators (`Pipeline` / `Branch` / `wrap` / `Swarm`) don't yet flow inner events through their own `session(...)` surfaces — known gap, see #1745 follow-ups.
- **No native binary** — JVM-only (≥ JDK 21). GraalVM and `jlink` bundles are Phase 2 priorities.
- **No A2A protocol yet** — agent-to-agent over network (Phase 2 / 3).
- **Inline-tool-call fallback model variance** — small Ollama models (e.g. `gemma3:4b`) reliably emit single tool calls via the inline format but may produce thin final-turn text after multi-step tool sequences. For multi-step reasoning, a tool-native model (`gpt-oss:20b-cloud` and similar) is the better fit.
- **No tool sandboxing** — tool executors run in-process with full JVM privileges. `grants { }` controls *which* tools an agent can call, not *what they can do* once invoked. Sandboxed execution (`ProcessSandbox` / `WasmSandbox` / `DockerSandbox` opt-in backends) is on the Phase 3 roadmap.
- **Text-only I/O today** — `LlmMessage.content: String` carries text. Image input (vision-capable adapters: Anthropic, OpenAI, Ollama, Gemini) and audio input land in Phase 2 alongside an `LlmContent` sealed-block evolution of the message model. Image generation (`ImageModelClient`: DALL-E, Imagen, Stability) and text-to-speech (`TTSModelClient`: OpenAI TTS, ElevenLabs, Google) are Phase 3.

For planned features beyond these limitations, see [docs/roadmap.md](docs/roadmap.md).

---

## Documentation

Topical guides:

- [**Website**](https://agents-kt.dev/) — distilled product tour: typed contracts, constrained tools, local-first runtime, swarm, install, and docs.
- [**Wiki**](https://github.com/Deep-CodeAI/Agents.KT/wiki) — expanded learning path and operational guides.
- [**Skills**](docs/skills.md) — agent skills, knowledge entries, shared catalogs, the lazy-vs-eager context model.
- [**Model & Tool Calling**](docs/model-and-tools.md) — agentic loop, typed tools via `@Generable`, inline-tool fallback, authorization, skill selection, budget caps.
- [**MCP Integration**](docs/mcp.md) — `mcp { server() }` client, `McpServer.from(agent)`, `McpRunner` standalone.
- [**Tool Error Recovery**](docs/error-recovery.md) — `onError { invalidArgs / deserializationError / executionError }`, `RepairResult.Fixed/Retry/Escalated/Unrecoverable`, default vs per-tool handlers.
- [**Agent Memory**](docs/memory.md) — `memory(MemoryBank())`, the three auto-injected tools, sharing memory across agents.
- [**Guided Generation**](docs/generation.md) — `@Generable`, `@Guide`, `@LlmDescription`, JSON-Schema generation, lenient deserializer, `PartiallyGenerated<T>`.
- [**Composition Operators**](docs/composition.md) — `then`, `/`, `*`, `forum`, `.loop {}`, `.branch {}`, single-placement rule, type algebra.
- [**InternalsAgent**](docs/internals-agent.md) — query agents-kt internals from your IDE via MCP (Cursor / Claude Desktop).
- [**Threat Model**](docs/threat-model.md) — five deployment scenarios + anti-patterns; self-classify your use case in 5 min.
- [**Production Hardening**](docs/production-hardening.md) — actionable checklist for "before going live."
- [**Regulated Deployment**](docs/regulated-deployment.md) — capability inventory, action log, decision points; EU AI Act mapping.
- [**Comparison**](docs/comparison.md) — Agents.KT vs LangChain / Semantic Kernel / AutoGen / raw MCP.
- [**Interceptors (design draft)**](docs/interceptors.md) — `onBefore*` family + `Decision` sealed type; not yet implemented (#1907).
- [**Roadmap**](docs/roadmap.md) — full Phase 1–4 feature plan.

---

## Current Release

`main` is currently `0.6.0` — an additive telemetry release on top of the v0.5.0 platform. **Token usage telemetry**: `onTokenUsage { usage -> }` exposes provider-reported `TokenUsage(promptTokens, completionTokens, cachedInputTokens, provider, model)` once per successful LLM round-trip, including end-of-stream usage for streaming adapters. **Provider constrained decoding**: agentic skills returning `@Generable` types now pass JSON Schema to supporting providers automatically (OpenAI `response_format.json_schema`, Ollama `format`, Anthropic structured-output tool), with parser retries still retained as defense-in-depth. **Streaming runtime**: `agent.session(input).events: Flow<AgentEvent<OUT>>` surfaces typed `Token` / `ToolCall*` / bracket events as the agentic loop runs. All three adapters (Ollama NDJSON, Anthropic SSE, OpenAI SSE) stream natively at the wire. Every composition operator (`then` / `wrap` / `Branch` / `Loop` / `Parallel` / `Forum` / `Swarm`) surfaces sessions with `agentId`-tagged inner events. **MCP-as-skills unification**: `mcp.toolSkills()` + `mcp.promptSkills()` + `mcp.resourceSkills()` — every MCP capability shape exposes as a `Skill` consumable in `skills { +... }`. `McpServer` gains DSLs to register prompts and resources alongside agents-as-tools, plus `McpStdioServer` and `McpRunner --stdio` expose the same server-side capability set over line-delimited stdio. `McpServerInfo` snapshots the full capability matrix. The 0.4 line (kotlin-reflect compileOnly, KSP @Generable, BouncyCastle hardening, wrap operator, three providers) is included.

Use Maven Central for published artifacts and tags for immutable release points.

---

## Getting Started

**Requirements:** JDK 21+, Kotlin 2.x, Gradle

```kotlin
// build.gradle.kts
dependencies {
    implementation("ai.deep-code:agents-kt:0.6.0")
}
```

Or clone and build from source:

```bash
git clone https://github.com/Deep-CodeAI/Agents.KT.git
cd Agents.KT
./gradlew test
```

Testing details — task names, integration test setup, mutation testing, how to write tests against the framework with a stub `ModelClient` — are in [**`docs/testing.md`**](docs/testing.md). IDE setup and build prerequisites are on the [**Building From Source**](https://github.com/Deep-CodeAI/Agents.KT/wiki/Building-From-Source) wiki page.

---

## Roadmap (highlights)

**Phase 1 — Core DSL** *(in progress)*: typed agents, skills, knowledge, composition operators (`then`, `/`, `*`, `forum`, `.loop`, `.branch`), MCP client + server, agent memory, `loadResource(path)` for prompts from classpath, agentic loop with full budget controls (`maxTurns` / `maxToolCalls` / `maxDuration` / `perToolTimeout` / `maxTokens` / `maxConsecutiveSameTool`), observability hooks (`onSkillChosen`, `onToolUse`, `onKnowledgeUsed`, `onError`, `onBudgetThreshold`, `Agent.observe { }`).

**Phase 2 — Runtime + Distribution** *(Q2 2026)*: remaining provider (Google), native CLI / jlink, `Tool<IN, OUT>` hierarchy, `grants {}` permissions, session model, Flow-based observability, **multimodal input** (image + audio content blocks; vision-capable adapters for Anthropic/OpenAI/Ollama/Gemini), `agent.json` serialization, Gradle plugin. *(Anthropic + OpenAI adapters landed in #1644 / #1656; KSP `@Generable` codegen shipped in v0.4.6; per-adapter native streaming overrides — Anthropic SSE, OpenAI SSE, Ollama NDJSON — shipped in v0.5.0; provider-level constrained decoding for `@Generable` outputs shipped in v0.6.0 via #1949.)*

**Phase 3 — Production** *(Q3 2026)*: Layer 2 Structure DSL, all 37 compile-time validations, AgentUnit, A2A protocol, file-based knowledge with RAG, OpenTelemetry, **sandboxed tool execution** (`SandboxedExecutor` with `ProcessSandbox` (Seatbelt / bwrap), `WasmSandbox` (Chicory), `DockerSandbox` backends — opt-in per tool, subprocess-shaped tools only, default executor stays in-process), **generative outputs** (`ImageModelClient` for DALL-E / Imagen / Stability, `TTSModelClient` for OpenAI / ElevenLabs / Google).

**Phase 4 — Ecosystem** *(Q4 2026)*: knowledge packs, NL → DSL generation, Skillify, visual editor, knowledge marketplace.

Full per-feature breakdown in [**docs/roadmap.md**](docs/roadmap.md).

---

## License

[MIT](LICENSE) — Deep-Code.AI
