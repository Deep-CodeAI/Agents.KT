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
- [x] Agentic execution loop — multi-turn tool calling with budget controls (`maxTurns`, `maxToolCalls`, `maxDuration`, `perToolTimeout`, `maxTokens`, `maxConsecutiveSameTool`) + `onToolUse` observability hook (#637, #963, #969)
- [x] Skill selection — manual `skillSelection {}` + automatic LLM routing when multiple skills match
- [x] `onError { Throwable -> }` — infrastructure-error observability hook (LLM transport, response parse, budget); pure observability — original exception always rethrows (#962)
- [x] `Agent.observe { event -> }` — sealed `PipelineEvent` bridges the four hooks (skill / tool / knowledge / error) into one typed stream; composes additively (#965)
- [x] `Agent.toString()` + `Agent.describe()` — readable single-line + multi-line debug output replacing the JVM identity-hash default (#970)
- [x] `onBudgetThreshold(threshold) { reason, usedPercent -> }` — pre-cap warning hook; fires once per `BudgetReason` when cumulative usage crosses the fraction, before the cap throws (#966)
- [x] `loadResource(path)` / `loadResourceOrNull(path)` — read agent system prompts from classpath resources; fail-fast at agent construction when path is missing; UTF-8 decoded; leading-slash normalized (#980)
- [ ] `>>` — security/education wrap

**Phase 2 — Runtime + Distribution** *(Q2 2026)*

*Priority:*
- [ ] `Tool<IN, OUT>` hierarchy + `McpTool<IN, OUT>` — MCP as native Tool inheritance, not a wrapper
- [ ] MCP client integration — `McpTool` instances consumable alongside local tools
- [ ] `grants { tools(...) }` — Layer 2 permissions use actual `Tool<*,*>` references
- [ ] Permission model: 3 states — Granted (auto-runs), Confirmed (user approval), Absent (unavailable)
- [ ] KSP annotation processor — compile-time `@Generable`; constrained decoding (Ollama) + guided JSON mode (Anthropic/OpenAI)
- [ ] Native CLI binary (GraalVM — no JRE required); `brew`, npm, pip, curl, apt
- [ ] jlink minimal JRE bundle for runtime (~35MB)

*Secondary:*
- [ ] Session model — multi-turn `AgentSession`, automatic compaction (`SUMMARIZE`, `SLIDING_WINDOW`, `CUSTOM`)
- [ ] Reactive context hooks — `beforeInference`, `afterToolCall` (context-mutating)
- [x] Agent memory — `MemoryBank`, `memory_read`/`memory_write`/`memory_search` auto-injected tools
- [ ] `.spawn {}` — independent sub-agent lifecycle, `AgentHandle<OUT>`, parent-managed join
- [ ] `Flow<PipelineEvent>` for reactive UIs + Pipeline-level events (`StageStarted`, `PipelineCompleted`, etc) — depends on streaming, sub-agents, sessions
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

**Phase 4 — Ecosystem** *(Q4 2026)*
- [ ] Knowledge packs — battle-tested prompt libraries for common domains
- [ ] Agent generation from natural language (NL → Kotlin DSL)
- [ ] Skillify — extract reusable skills from session transcripts
- [ ] Visual structure editor, UML bidirectional conversion
- [ ] Knowledge marketplace

---

