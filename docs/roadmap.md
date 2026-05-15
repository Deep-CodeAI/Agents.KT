[← Back to README](../README.md)

## Roadmap

**Phase 1 — Core DSL** *(in progress)*
- [x] `Agent<IN, OUT>` with SRP enforcement
- [x] `Agent.prompt` — base context string for the LLM
- [x] Skills-only execution — all agents run through `skills { implementedBy { } }`
- [x] `Skill.description` — sells the skill to the LLM alongside its type signature
- [x] `Skill.knowledge("key", "description") { }` — named lazy context providers; `loadFile()` inside lambdas
- [x] `Skill.toLlmDescription()` — auto-generated markdown (name, types, description, knowledge index); `llmDescription("...")` override
- [x] `Skill.toLlmContext()` — full context: description markdown + all knowledge content
- [x] `Skill.knowledgeTools()` / `KnowledgeTool(name, description, call)` — tools model with lazy per-entry loading
- [x] `then` — sequential pipeline with composed execution (no runtime casts)
- [x] `/` — parallel fan-out with coroutine concurrency
- [x] `*` — forum shorthand with concurrent participants, last-agent captain, and `onMentionEmitted`
- [x] `forum { participant(...); captain(...); allowForumReturn(...) }` — explicit forum roles and finalization permissions
- [x] Single-placement enforcement across all structure types
- [x] `.loop {}` — iterative execution with `(OUT) -> IN?` feedback block
- [x] `.branch {}` — conditional routing on sealed types, composable with `then`
- [x] `@Generable("desc")` / `@Guide` / `@LlmDescription` — runtime reflection: `toLlmDescription()`, `jsonSchema()`, `promptFragment()`, `fromLlmOutput<T>()`, `PartiallyGenerated<T>`
- [x] `model { }` — Ollama backend; `host`, `port`, `temperature`; injectable `ModelClient` for tests; auto-fallback to inline JSON tool-call format for models without native tool support (#706)
- [x] `model { claude(name); apiKey = ... }` — Anthropic Messages API adapter mapping `LlmMessage`/`LlmResponse` to/from Anthropic's structured `tool_use` / `tool_result` content blocks; live integration tests against `claude-haiku-4-5-20251001` (#1644)
- [x] `model { openai(name); apiKey = ... }` — OpenAI Chat Completions adapter; `tool_calls` ↔ `tool_call_id` paired by synthesized id, `parameters` schema field (vs Anthropic's `input_schema`); live integration tests against `gpt-4o-mini` (#1656)
- [x] Agentic execution loop — multi-turn tool calling with budget controls (`maxTurns`, `maxToolCalls`, `maxDuration`, `perToolTimeout`, `maxTokens`, `maxConsecutiveSameTool`) + `onToolUse` observability hook (#637, #963, #969)
- [x] Skill selection — manual `skillSelection {}` + automatic LLM routing when multiple skills match
- [x] `onError { Throwable -> }` — infrastructure-error observability hook (LLM transport, response parse, budget); pure observability — original exception always rethrows (#962)
- [x] `Agent.observe { event -> }` — sealed `PipelineEvent` bridges the four hooks (skill / tool / knowledge / error) into one typed stream; composes additively (#965)
- [x] `Agent.toString()` + `Agent.describe()` — readable single-line + multi-line debug output replacing the JVM identity-hash default (#970)
- [x] `onBudgetThreshold(threshold) { reason, usedPercent -> }` — pre-cap warning hook; fires once per `BudgetReason` when cumulative usage crosses the fraction, before the cap throws (#966)
- [x] `loadResource(path)` / `loadResourceOrNull(path)` — read agent system prompts from classpath resources; fail-fast at agent construction when path is missing; UTF-8 decoded; leading-slash normalized (#980)
- [x] `wrap` — teacher→student prompt-override operator (`teacher wrap student` returns a `Pipeline<IN, OUT>` where the teacher's `String` output becomes the student's system prompt for that one call; restored after). Two framings: *education* (one generalist student specialized by many teachers) and *security* (the student's task surface is locked to what the teacher emits). The PRD calls this the `>>` operator; Kotlin can't overload `>>` so the function is named `wrap` (#1698)

**Phase 2 — Runtime + Distribution** *(Q2 2026)*

*Priority:*
- [ ] `Tool<IN, OUT>` hierarchy + `McpTool<IN, OUT>` — MCP as native Tool inheritance, not a wrapper
- [ ] MCP client integration — `McpTool` instances consumable alongside local tools
- [ ] `grants { tools(...) }` — Layer 2 permissions use actual `Tool<*,*>` references
- [ ] Permission model: 3 states — Granted (auto-runs), Confirmed (user approval), Absent (unavailable)
- [x] KSP annotation processor — compile-time `@Generable` codegen: shape validation (#1700), schema emitter + field-type validation (#1701), sealed-root schema (#1702), `toLlmDescription()` + multi-constant cache (#1703), `constructFromMap` codegen (#1704), drop runtime `kotlin-reflect` + empty-variants gate (#1705). Ships as `agents-kt-ksp` module
- [ ] Provider-level constrained decoding (Ollama `format: schema`) + guided JSON mode (Anthropic / OpenAI `response_format: json_schema`) — wire `@Generable` JSON schemas through to provider request payloads so the model is forced to emit valid shape (eliminates retry-on-parse loops)
- [ ] Native CLI binary (GraalVM — no JRE required); `brew`, npm, pip, curl, apt
- [ ] jlink minimal JRE bundle for runtime (~35MB)

*Secondary:*
- [ ] Session model — multi-turn `AgentSession`, automatic compaction (`SUMMARIZE`, `SLIDING_WINDOW`, `CUSTOM`)
- [ ] Reactive context hooks — `beforeInference`, `afterToolCall` (context-mutating)
- [x] Agent memory — `MemoryBank`, `memory_read`/`memory_write`/`memory_search` auto-injected tools
- [ ] `.spawn {}` — independent sub-agent lifecycle, `AgentHandle<OUT>`, parent-managed join
- [x] Streaming foundation — `LlmChunk` sealed type (`TextDelta` / `ToolCallStarted` / `ToolCallArgumentsDelta` / `ToolCallFinished` / `End`) + `ModelClient.chatStream(messages): Flow<LlmChunk>` with a default impl that wraps `chat()` so non-streaming providers keep working unchanged. Provider-native streaming (Anthropic SSE, OpenAI SSE, Ollama `stream: true`) overrides land per-adapter. `LlmChunk` stays narrow — no agentic concepts like `skillName` / `agentId` (#1722)
- [x] Streaming session surface — `AgentEvent` sealed hierarchy (`Token` / `ToolCallStarted` / `ToolCallArgumentsDelta` / `ToolCallFinished` / `SkillStarted` / `SkillCompleted` / `Completed<OUT>` / `Failed`, every event carrying `agentId`), `AgentSession<OUT>` (cold `events: Flow<AgentEvent<OUT>>` + `suspend fun await(): OUT`), and free function `Agent<IN, OUT>.session(input): AgentSession<OUT>` (#1736). Existing `Agent.invokeSuspend` delegates to a new internal `invokeSuspendForSession` with a no-op skill listener — backward-compat byte-for-byte. Today emits only bracket events (`SkillStarted` / `SkillCompleted` / `Completed` / `Failed`) — the `Token` / `ToolCall*` subtypes are defined and ready for consumers but not yet emitted (next entry). Integration coverage: failure-path identity-preserved `cause`, concurrent sessions, agentic-stub bracketing, live-LLM π-to-20-decimals against Ollama (#1737), and prompt-cancellation of the events collector (#1738).
- [ ] Agentic-loop rewire onto `FlowCollector<AgentEvent>` — `Token` and `ToolCall*` events fire mid-loop; cancellation propagates into `chatStream` HTTP suspension; `tokensUsed` gets threaded through `SkillCompleted` / `Completed`. Step 3 of the v0.5.0 plan.
- [ ] Per-adapter native streaming overrides — Anthropic SSE, OpenAI SSE, Ollama `stream: true` — emit real partial chunks instead of the default `chat()`-wrap. See [v0.5.0 streaming premortem](premortem-0.5.0-streaming.md)
- [ ] `Flow<PipelineEvent>` for reactive UIs + Pipeline-level events (`StageStarted`, `PipelineCompleted`, etc) — built on top of `LlmChunk`; depends on sub-agents and sessions
- [ ] **Multimodal input** — vision and audio content blocks on LLM messages.
  - **Image input:** vision-capable adapters accept image bytes + media type as a content block alongside text. Targets: Anthropic (`image` content blocks), OpenAI (`image_url` / base64 in content), Ollama (`llava` / `bakllava` via `images` field), Google Gemini.
  - **Audio input:** true audio input (Gemini, GPT-4o-audio) — `LlmContent.Audio` block. Optional STT-only helper `audio.transcribe(file)` for the Whisper-style use case.
  - **Architectural change:** `LlmMessage.content: String` needs to evolve into a `List<LlmContent>` sealed type (Text / Image / Audio blocks). Binary-compat risk: add a sibling `contentBlocks: List<LlmContent>?` field first with the existing String form auto-coerced into a single Text block; deprecate the String form once the API surface settles. Typed boundaries are unaffected — `Agent<Image, String>` (image classifier) and `Agent<AudioClip, String>` (transcriber) become coherent agent shapes.
- [ ] Serialization — `agent.json`, A2A AgentCard
- [ ] JAR bundles and folder-based assembly
- [ ] Gradle plugin

**Phase 3 — Production** *(Q3 2026)*
- [ ] Layer 2: Full Structure DSL with delegates, grants, authority, routing, escalation
- [ ] All 37 compile-time validations enforced by Gradle plugin
- [ ] AgentUnit testing framework — unit, semantic (LLM-as-judge), Skill Coverage metrics
- [ ] A2A protocol support (server + client)
- [ ] File-based knowledge: `skill.md`, `reference`, `examples`, `checklist` + RAG pipeline
- [ ] Production observability: OpenTelemetry traces
- [ ] Team DSL — swarm coordination (if isolated execution available)
- [ ] **Generative outputs (image + audio)** — sibling client interfaces to `ModelClient` for non-chat model families.
  - `ImageModelClient.generate(prompt, options): ImageBytes` — text → image. Adapters: OpenAI DALL-E 3, Google Imagen, Stability. Optional streaming via `generateStream(...): Flow<LlmChunk.ImageDelta>` for partial-preview UX.
  - `TTSModelClient.synthesize(text, voice, options): AudioBytes` — text → speech. Adapters: OpenAI TTS, ElevenLabs, Google Cloud TTS. Streaming via `LlmChunk.AudioDelta(pcmChunk)` for low-latency playback (relevant for IDE voice agents, chat UIs).
  - These keep the typed-boundary identity: `Agent<String, ImageBytes>` and `Agent<TextRequest, AudioBytes>` are first-class. Composition operators (`then`, `wrap`) work unchanged across modalities.
- [ ] **Sandboxed tool execution** — `SandboxedExecutor` interface with three backends, opt-in per tool (`tool(..., sandbox = ...)`) or per skill (`sandbox { }` block). Default executor stays in-process for backward compatibility. **Scope (lesson from Claude Code's implementation):** sandbox only applies to subprocess-shaped tools — tools whose executor shells out via `ProcessBuilder` or invokes external binaries. In-process Kotlin lambdas don't get OS-level isolation because `grants { }` + frozen agents already bound them; bolting on a sandbox is overkill that just makes the framework feel heavier.
  - `ProcessSandbox` — subprocess executor with env / cwd / timeout / network constraints. Backends: **Seatbelt** on macOS (the framework behind `sandbox-exec`; built into the OS), `bwrap` (bubblewrap) on Linux as the primary, `firejail` as the fallback. On WSL2 same as Linux; WSL1 unsupported (no namespace support). Plain `ProcessBuilder` with a loud warning on platforms with no native sandboxing tool. **Most pragmatic** — every dev box has at least one path. Cribs profile shape + socat-proxy plumbing from [`anthropic-experimental/sandbox-runtime`](https://github.com/anthropic-experimental/sandbox-runtime) (Anthropic's open-source Linux bwrap reference).
  - **Network sub-policy:** outbound blocked by default; allowlist via `sandbox.network.allowedDomains`. A proxy server (running outside the sandbox) intercepts DNS + connections and gates by hostname. **TLS caveat:** the default proxy doesn't terminate TLS — it allows/denies by hostname only. Allowing broad domains (`github.com`, `googleapis.com`) leaves room for domain-fronting; consumers needing real traffic inspection plug in their own MITM proxy. Document this explicitly so it's not a surprise.
  - **Permission/sandbox interaction:** sandbox path config and `grants { }` path config *merge* — both layers apply (matches Claude Code semantics). Sandbox cannot accidentally widen what `grants` denies. A tool with both must satisfy both.
  - `WasmSandbox` — JAR-embedded WASM runtime via Chicory (pure-Java; no host setup). Tools compiled to WASM; filesystem and network capabilities granted explicitly at registration. **Most truly embedded** — works anywhere a JVM runs.
  - `DockerSandbox` — opt-in extras module (`agents-kt-docker-sandbox`) via `docker-java`. Talks to whatever Docker daemon the host already runs. **Not embeddable** — library ships in the JAR, daemon does not. For teams that already operate Docker.
  - Why this axis matters: today `grants { tools(writeFile, compile) }` controls *which* tools an agent can call; sandboxing controls *what those tools can do* once invoked. Pairs with frozen agents + typed args to give a security model that's strictly stronger than "trust the executor lambda."

**Phase 4 — Ecosystem** *(Q4 2026)*
- [ ] Knowledge packs — battle-tested prompt libraries for common domains
- [ ] Agent generation from natural language (NL → Kotlin DSL)
- [ ] Skillify — extract reusable skills from session transcripts
- [ ] Visual structure editor, UML bidirectional conversion
- [ ] Knowledge marketplace

---

