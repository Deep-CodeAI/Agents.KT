[← Back to README](../README.md)

## Roadmap

### Release narrative

```
0.5.0   Agents with boundaries                       — shipped
0.6.0   Boundaries you can audit                     — current focus (epic [#1911](../../issues/1911))
0.7.0   Boundaries you can enforce externally
```

**0.6.0 hero feature:** the **permission manifest / capability graph** ([#1912](../../issues/1912)) — a deterministic YAML/JSON artifact showing every agent / skill / tool / memory access / MCP endpoint / provider / budget / policy boundary in a system. Build-time evidence for security review; the manifest hash ([#1913](../../issues/1913)) propagates into every runtime audit event so dynamic behaviour ties back to the signed-off capability graph.

The 0.6.0 epic ([#1911](../../issues/1911)) tracks the full acceptance criteria. The phase layout below remains time-based; the release-arc tags below each item show which release that item targets.

---

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

*Priority — 0.6.0 hero:*
- [ ] **Permission manifest / capability graph** — `pipeline.permissionManifest { }` DSL on agents and compositions; `writeYaml(file)` / `writeJson(file)` emit deterministic output; Gradle task `agentManifest` plus `verifyAgentManifest` that fails CI when high-risk changes appear (new high-risk tool, tool gains network/write access, MCP exposure widens, human-oversight removed, budgets relaxed, provider switches local→remote). Captures agents, skills, tools, memory R/W, budgets, MCP client/server caps, providers (secrets masked), guardrail hooks, composition structure. Lives in `:agents-kt-manifest` (zero vendor deps). The hero feature that turns the boundary-first runtime into something an auditor can sign off. ([#1912](../../issues/1912))
- [ ] **Manifest hash + request/session IDs in runtime audit events** — `AgentRuntimeContext` carries `requestId` (UUIDv4 per `invoke`), `sessionId` (per `agent.session()`), `manifestHash` (sha256 of the deterministic manifest). Every `PipelineEvent` / `AgentEvent` includes these three; consumed by the OTel bridge ([#1908](../../issues/1908)) and the JSONL exporter ([#1914](../../issues/1914)). Closes the loop from build-time evidence to runtime behaviour. ([#1913](../../issues/1913))
- [ ] **JSONL audit log exporter** — append-only, one event per line, grep/`jq`-friendly. Schema covers `requestId / sessionId / manifestHash / agentId / skillId / toolId / eventType / timestamp / inputType / outputType / budgetState / guardrailDecision / mcpClientId / provider / model`. Lives in `:agents-kt-observability`. Sibling to the OTel bridge ([#1908](../../issues/1908)) for teams that need a deterministic on-disk record. ([#1914](../../issues/1914))
- [ ] **Declarative tool sandbox policy DSL** *(0.6.0 — declarative only, enforcement in 0.7.0)* — `tool(..., policy { risk = ToolRisk.Medium; filesystem { read("/uploads/**"); writeNone() }; network { denyAll() } })`. Captured in the permission manifest verbatim. Audit events note `toolPolicy.risk`. The enforcement layer is sibling [#1916](../../issues/1916). ([#1915](../../issues/1915))

*Priority — 0.6.0 platform:*
- [x] `Tool<IN, OUT>` hierarchy + `McpTool<IN, OUT>` — typed tool inheritance refining the current skills-shape ([#1948](../../issues/1948)). MCP capabilities still ship as `Skill<Map<String,Any?>, String>` via `McpClient.toolSkills()`, and now also as first-class `McpTool<Map<String,Any?>, String>` handles via `McpClient.tools()`. The typed-tool layer is additive and gives `grants { tools(...) }` / manifests a shared local+MCP boundary object.
- [x] MCP client integration — `McpClient.toolSkills()` / `promptSkills()` / `resourceSkills()` expose every MCP capability as a `Skill` consumable in `skills { +... }`. The `McpTool` *type-hierarchy* refinement (above) is a future ergonomic upgrade; the user-facing feature shipped in 0.5.0 as the skills-shape (#1795 / #1796 / #1810). `McpServer` ships DSLs to register prompts and resources alongside agents-as-tools, plus `McpServerInfo` for the full capability snapshot
- [ ] **McpServer hardening** — first-class incoming auth (`McpServerAuth`), origin/host allowlist on HTTP transport, `ClientPrincipal` plumbed to tool execution, capability negotiation filtered per client, `clientPolicy { client("ui") { allowSkill(...); denyTool(...); maxRequestsPerMinute = 60 } }` DSL, audit event per accepted/rejected MCP request with `mcpClientId` / decision reason. Default-deny outside localhost. Removes the README "no incoming auth on McpServer / no origin validation" limitations. ([#1902](../../issues/1902))
- [ ] **Google Gemini provider adapter** — fourth `ModelClient` alongside Anthropic / OpenAI / Ollama; native SSE streaming override. Closes the "three providers only" objection without shifting Agents.KT into a provider-breadth race against Koog. ([#1917](../../issues/1917))
- [ ] `grants { tools(...) }` — Layer 2 static permission DSL referencing `Tool<*,*>` instances. **Folded into the permission-manifest issue** ([#1912](../../issues/1912)) — the manifest *is* the serialised view of every agent's grants; the DSL block is the input, the YAML/JSON is the output. Depends on the typed `Tool<IN,OUT>` hierarchy ([#1948](../../issues/1948))
- [ ] Permission model: 3 states — Granted / Confirmed / Absent. **Folded into the guardrails issue** ([#1907](../../issues/1907)): *Granted* = `Allow` or no interceptor registered; *Confirmed* = `Escalate(reason, reviewerRole)` resumed by host app; *Absent* = existing pre-guardrail `allowedToolMap` rejection now surfaced via `onUnauthorizedToolCall`
- [x] KSP annotation processor — compile-time `@Generable` codegen: shape validation (#1700), schema emitter + field-type validation (#1701), sealed-root schema (#1702), `toLlmDescription()` + multi-constant cache (#1703), `constructFromMap` codegen (#1704), drop runtime `kotlin-reflect` + empty-variants gate (#1705). Ships as `agents-kt-ksp` module
- [ ] Provider-level constrained decoding (Ollama `format: schema`) + guided JSON mode (Anthropic / OpenAI `response_format: json_schema`, Gemini `responseSchema`) — wire the KSP-emitted `@Generable` JSON schemas through to provider request payloads so the model is forced to emit valid shape; eliminates the argument-repair retry loop (up to 8 retries today) for providers that support it. Schemas already emitted by `agents-kt-ksp/SchemaEmitter`; they just aren't threaded into provider payloads yet. ([#1949](../../issues/1949))
- [ ] Native CLI binary (GraalVM — no JRE required); `brew`, npm, pip, curl, apt. Subcommands: `manifest` (emit), `inspect` (show manifest for a JAR), `verify` (diff against baseline, fail on policy relaxation). 0.7.0 deliverable. ([#1923](../../issues/1923))
- [ ] jlink minimal JRE bundle for runtime (~35MB)

*Secondary:*
- [ ] Session model — multi-turn `AgentSession`, automatic compaction (`SUMMARIZE`, `SLIDING_WINDOW`, `CUSTOM`)
- [ ] **`onBefore*` interceptor family** — Rails-style `onBeforeSkill` / `onBeforeToolCall` / `onBeforeTurn` returning a sealed `Decision { Proceed | ProceedWith(args) | Deny(reason) | Substitute(result) }`. Sibling to today's post-hoc observer hooks (`onToolUse` / `onSkillChosen` / `onError`). Unifies per-client tool policy (McpServer), action confirmation, prompt-injection filtering (one-liner: `onBeforeTurn { msgs -> if (filter.flag(msgs)) Decision.Deny(...) else Decision.Proceed }`), and uniform `perToolTimeout` wrapping. Chain semantics: registration order, all run, first non-`Proceed` wins. ([#1907](../../issues/1907), blocks [#1902](../../issues/1902) and feeds [#1908](../../issues/1908))
- [x] Agent memory — `MemoryBank`, `memory_read`/`memory_write`/`memory_search` auto-injected tools
- [ ] `.spawn {}` — independent sub-agent lifecycle, `AgentHandle<OUT>`, parent-managed join
- [x] Streaming foundation — `LlmChunk` sealed type (`TextDelta` / `ToolCallStarted` / `ToolCallArgumentsDelta` / `ToolCallFinished` / `End`) + `ModelClient.chatStream(messages): Flow<LlmChunk>` with a default impl that wraps `chat()` so non-streaming providers keep working unchanged. Provider-native streaming (Anthropic SSE, OpenAI SSE, Ollama `stream: true`) overrides land per-adapter. `LlmChunk` stays narrow — no agentic concepts like `skillName` / `agentId` (#1722)
- [x] Streaming session surface — `AgentEvent` sealed hierarchy (`Token` / `ToolCallStarted` / `ToolCallArgumentsDelta` / `ToolCallFinished` / `SkillStarted` / `SkillCompleted` / `Completed<OUT>` / `Failed`, every event carrying `agentId`), `AgentSession<OUT>` (cold `events: Flow<AgentEvent<OUT>>` + `suspend fun await(): OUT`), and free function `Agent<IN, OUT>.session(input): AgentSession<OUT>` (#1736). Existing `Agent.invokeSuspend` delegates to a new internal `invokeSuspendForSession` with a no-op skill listener — backward-compat byte-for-byte. Today emits only bracket events (`SkillStarted` / `SkillCompleted` / `Completed` / `Failed`) — the `Token` / `ToolCall*` subtypes are defined and ready for consumers but not yet emitted (next entry). Integration coverage: failure-path identity-preserved `cause`, concurrent sessions, agentic-stub bracketing, live-LLM π-to-20-decimals against Ollama (#1737), and prompt-cancellation of the events collector (#1738).
- [x] Agentic-loop rewire onto `FlowCollector<AgentEvent>` — `Token` and `ToolCall*` events fire mid-loop; `tokensUsed` threaded through `SkillCompleted` / `Completed`. Shipped in 0.5.0 (#1739 / #1740). **Partial:** synchronous skill bodies and blocking HTTP reads are not coroutine-cancellable mid-call yet — the `sendAsync` adapter migration (step 4) is still pending and pairs with [#1903](../../issues/1903) for the session-aware `perToolTimeout` fix.
- [ ] **Enforce `perToolTimeout` on session-aware tool path** — close the documented gap at `AgenticLoop.kt:392-405` where session-aware tool execution (`sessionExecutor`) bypasses `budget.perToolTimeout`. Migrate to coroutine-cancellable async execution so the timeout cancels underlying HTTP I/O, not just a worker thread. Best landed *after* `onBefore*` interceptors so the timeout wraps uniformly. ([#1903](../../issues/1903))
- [ ] **Streaming docs reconcile** — README.md:162 ("no per-adapter native streaming yet") contradicts :163 / :193 ("all three adapters stream natively"). Sweep Limitations / Roadmap bullets and tag each as `shipped` / `experimental` / `planned`. ([#1901](../../issues/1901))
- [x] Per-adapter native streaming overrides — Anthropic SSE (`ClaudeClient.chatStream`), OpenAI SSE (`OpenAiClient.chatStream`), Ollama NDJSON `stream: true` (`OllamaClient.chatStream`) all emit real partial chunks at the wire. Live integration tests measure 19 / 2 / 19 chunks per response respectively. See [v0.5.0 streaming premortem](premortem-0.5.0-streaming.md)
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
- [ ] **Production observability — vendor-neutral `ObservabilityBridge` + adapter modules.** Core ships a zero-dep `:agents-kt-observability` module exposing `ObservabilityBridge { onPipelineEvent / onAgentEvent / onInterceptorDecision }` and an `agent.observe(bridge)` extension that wires both event surfaces plus the `onBefore*` decisions ([#1907](../../issues/1907)) into the bridge. Adapters live in separate Gradle modules so local-first users never pull vendor SDKs.
  - [ ] `:agents-kt-otel` — OpenTelemetry adapter using the GenAI semantic conventions: skill = root span, model turn = child (`gen_ai.operation.name=chat`, `gen_ai.system`, token-usage attrs), tool call = grandchild (`tool.name`, `tool.duration_ms`), errors as span status, budget threshold / interceptor decisions as span events. Parent-context propagation via `Context.current()`. ([#1908](../../issues/1908), blocked-by [#1907](../../issues/1907))
  - [ ] `:agents-kt-langsmith` — LangSmith run-tree adapter (chain → llm → tool runs), async dispatch with backpressure. ([#1909](../../issues/1909), blocked-by [#1908](../../issues/1908))
  - [ ] `:agents-kt-langfuse` — Langfuse traces / spans / generations adapter. ([#1910](../../issues/1910), blocked-by [#1908](../../issues/1908))
- [ ] **Threat-model + deployment-pattern guide** — `docs/threat-model.md` with four worked scenarios (safe local assistant; internal business tool; MCP server behind gateway; anti-patterns), each calling out which Agents.KT guardrails apply and which gaps the deployer must close themselves. Linked from README security section and `SECURITY.md`. ([#1904](../../issues/1904))
- [ ] **Release-signing hardening** — replace the no-passphrase GPG example in the publishing guide with a passphrase-protected default; add a CI-signing section (secrets-manager-injected passphrase, short-lived subkey, or OIDC-to-signing-service); demote the no-protection variant to a clearly-labelled "local-only sandbox keys" subsection. ([#1905](../../issues/1905))
- [ ] **Three killer 0.6.0 demos** — *(1)* safe MCP filesystem agent (read-only allowlist, rejection visible in audit log), *(2)* typed approval workflow with `Escalate` decisions for high-risk paths, *(3)* multi-agent audit pipeline binding every model + tool call to the manifest hash. Each lives in `examples/<name>/`, runs against Ollama by default, emits manifest + JSONL audit on one invocation. Validates the 0.6.0 story end-to-end. ([#1918](../../issues/1918))
- [ ] **Production hardening checklist + regulated deployment guide** — `docs/production-hardening.md` checkbox list (tool allowlists, MCP auth, conservative budgets, output wrapping, audit logs, manifest review in CI, etc.) and `docs/regulated-deployment.md` for finance / healthcare / public-sector buyers (audit retention, evidence pack, manifest-hash chain-of-custody). Companion to threat-model ([#1904](../../issues/1904)). ([#1919](../../issues/1919))
- [ ] **AI Act-aligned whitepaper** — 8–12 page engineering-guidance document (explicitly **not** legal advice) on bounded agent systems, the manifest as static evidence, audit events as dynamic evidence, human-oversight hooks, shared-responsibility model. Timed for 2026 AI-governance attention. ([#1921](../../issues/1921))
- [ ] **README + landing repositioning** — boundary-first / auditable register; "what Agents.KT owns" + "what it doesn't try to own" sections; marketing-register and compliance-language audit (avoid "fastest" / "fully compliant"; keep "auditable" / "least privilege" / "compliance-supporting"). Feeds off the comparison page ([#1906](../../issues/1906)). ([#1922](../../issues/1922))
- [ ] **Scarf integration + Maven adoption verification** — set up Scarf on `ai.deep-code:agents-kt:*`, 30-day baseline before public adoption claims, keep public wording soft ("Maven pull-through stronger than GitHub stars suggest") until verified. Outreach template prepped but not sent. ([#1920](../../issues/1920))
- [ ] Team DSL — swarm coordination (if isolated execution available)
- [ ] **Generative outputs (image + audio)** — sibling client interfaces to `ModelClient` for non-chat model families.
  - `ImageModelClient.generate(prompt, options): ImageBytes` — text → image. Adapters: OpenAI DALL-E 3, Google Imagen, Stability. Optional streaming via `generateStream(...): Flow<LlmChunk.ImageDelta>` for partial-preview UX.
  - `TTSModelClient.synthesize(text, voice, options): AudioBytes` — text → speech. Adapters: OpenAI TTS, ElevenLabs, Google Cloud TTS. Streaming via `LlmChunk.AudioDelta(pcmChunk)` for low-latency playback (relevant for IDE voice agents, chat UIs).
  - These keep the typed-boundary identity: `Agent<String, ImageBytes>` and `Agent<TextRequest, AudioBytes>` are first-class. Composition operators (`then`, `wrap`) work unchanged across modalities.
- [ ] **Sandboxed tool execution** *(0.7.0 enforcement layer — depends on the declarative policy DSL in 0.6.0, [#1915](../../issues/1915))* ([#1916](../../issues/1916)) — `SandboxedExecutor` interface with three backends, opt-in per tool (`tool(..., sandbox = ...)`) or per skill (`sandbox { }` block). Default executor stays in-process for backward compatibility. **Scope (lesson from Claude Code's implementation):** sandbox only applies to subprocess-shaped tools — tools whose executor shells out via `ProcessBuilder` or invokes external binaries. In-process Kotlin lambdas don't get OS-level isolation because `grants { }` + frozen agents already bound them; bolting on a sandbox is overkill that just makes the framework feel heavier.
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
- [ ] **Comparison page** — `docs/comparison.md` with a feature matrix vs LangChain (Py + LangChain4j), Microsoft Semantic Kernel, AutoGen, and a raw MCP client; covers typed `Agent<IN,OUT>`, runtime tool allowlist, MCP client/server, native streaming, budgets, sandboxing, KSP/compile-time validation, language, local-first model support; honest "where Agents.KT is weaker" subsection. ([#1906](../../issues/1906))

---
