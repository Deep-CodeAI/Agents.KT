# Changelog

All notable changes to Agents.KT are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Pre-1.0, minor bumps may add new public API; existing API surface is preserved.

## [Unreleased]

### Added — `@Generable` schemas in the permission manifest (manifest v2, #3875)

- Manifests gain a top-level **`schemas`** section: JSON Schema for every `@Generable` IN/OUT type
  in the agent graph (KSP-aware cache-then-reflection probe), keyed by FQN with per-schema sha256,
  folded into `manifestHash` — a type change now bumps the manifest. Reviewers see shapes, not
  just names.
- **Manifest format v2**; loaded manifests preserve their own version (fixed: `fromJson`
  previously stamped the current constant over a baseline's version), and version differences
  verify with a non-fatal `manifest.version.changed` **info** finding (`ok` is now
  severity-aware — behavior-preserving, all pre-existing finding types are "high"). 3 tests.

### Added — `executor { args, env -> }` + the first `ToolEnvironment` slice (#2889 / #2883)

- **New executor shape:** `tool { policy { … }; executor { args, env -> … } }` — `env` is a
  per-call, policy-gated `ToolEnvironment` (v1 ABI: `readText` / `writeText` / `env(name)`); an
  operation the declared policy doesn't grant throws `ToolPolicyViolation` **before** it happens
  (paths normalized, fail-closed without a declaration). Both loop chokepoints are covered through
  the single executor seam — no loop changes.
- **Single-arg `executor { args -> }` keeps compiling and running** (back-compat pinned by test);
  the builder form carries a `WARNING` deprecation pointing at the new shape, per the one-release
  migration window. Subprocesses stay with `processTool`; blobs/clock/ledger-envelope recording
  land with the rest of #2883. 4 tests.

### Added — mechanical `-SNAPSHOT`-on-main enforcement (#4428)

- **`checkSnapshotPolicy`** Gradle task: a non-`-SNAPSHOT` version is only legal on the tagged
  release commit itself; anything else fails with the runbook-step-8 hint. CI runs it on every
  push to `main` (release-PR refs exempt by event type — they legitimately carry the release
  version before the tag exists; the task is deliberately not wired into `check` for the same
  reason). Closes the enforcement gap the runbook's post-release bump rule left open.

### Added — ToolPolicy ↔ capability comparator + `exec` capability (#2887)

- **`ToolPolicy.exec`** — declared subprocess stance (`exec { allow() }` / `exec { deny() }`;
  legacy manifests parse as `unspecified`). Serialized in manifest JSON/YAML; the manifest
  verifier flags `tool.exec.widened` on an unspecified/deny → allow jump (narrowing passes).
- **`ToolPolicyCapabilityComparator`** (agents-kt-detekt) — the declare-vs-do gate: for
  `tool { policy { … }; executor { … } }` declarations, the executor body's statically-extracted
  capabilities must be a subset of what the policy grants; using more than declared fails the
  build with a widen-or-remove hint. Over-declaration passes (a manifest-review concern).
  Un-policied tools stay `ToolBodyForbiddenApis`' business. Syntactic, callee-name based —
  same honest limits as the extractor. 11 new tests across the three modules.

### Added — built-in forum captains (#3877, P2.1)

- **`consensusCaptain(quorum)`** — N identical member verdicts or fail loud with the full tally;
  **`weightedCaptain(weights)`** — weighted vote keyed by panelist name (default 1.0);
  **`byzantineCaptain()`** — median of numeric verdicts (1-d geometric median, robust to
  ⌈n/2⌉−1 adversarial members; vector Krum is a tracked follow-up). All three are deterministic
  transcript captains — the strategy name is the captain's agent name, so audit events carry
  which aggregation decided the verdict. 5 tests through real forum deliberations.

### Added — `HumanGateRegistry`: the named HITL adapter (#3868, P1.5)

- **`gates.guard(agent, input)`** returns `GateOutcome.Completed(output)` or
  `GateOutcome.Paused(gate)` when a tool calls `humanApproval { }` / `interrupt(...)`. The
  `PendingGate` carries gateId / reason / payload for the reviewer; `approve(reviewer, comment)` /
  `reject(...)` / `resolve(HumanDecision, ...)` resumes from the snapshot exactly where the run
  left off (manifest-hash restore guard applies) and resolves exactly once.
- Snapshots are also persisted to an optional `SnapshotStore` as crash evidence; full
  post-restart rehydration (re-supplying agent + input) is a tracked follow-up. Audit events ride
  the existing #2489 channel. 4 tests.

### Added — `loopUntil` + `evalGate` (#3870, P1.4)

- **`agent.loopUntil(maxIterations, feedback?) { predicate }`** (also on `Pipeline`) — the named
  reflexion / evaluator-optimizer shape: re-run until the predicate approves the output, feeding
  `feedback(out)` (or the output itself when `IN == OUT`) back as input. Named `loopUntil` rather
  than a `loop { until { } }` DSL block so the existing `loop { next }` trailing-lambda overload
  stays source-compatible.
- **`evalGate(rubric, threshold)`** — pass/fail gate over the LLM-as-judge rubric (one judge call
  per check, `lastVerdict` keeps the rationale; threshold validated against the rubric's range).
  7 tests incl. the full reflexion shape against a scripted judge.

### Added — speculative execution: `firstOf` / `.speculative(n)` (#3869, P1.3)

- **`firstOf(a, b)`** races distinct agents; **`agent.speculative(3)`** races the same agent
  against itself. First **success** wins at the winner's latency; losers are cancelled but not
  awaited (sacrificial-worker precedent — suspending losers stop promptly, blocking bodies finish
  in the background, discarded). A failing branch doesn't settle the race; all-fail throws.
- `onRaceSettled { winner, cancelled, elapsedMillis -> }` audit signal;
  `firstOf.session(input)` streams every racer's events and completes under the winner's id.
- Budget honesty documented: losers' partial tokens are real provider spend — bound N;
  cross-branch accounting of cancelled partial usage is a tracked gap. 6 tests.

### Added — built-in aggregators on `/` (#3872, P1.2)

- **`(a / b / c).aggregate { … }`** — one-line ensemble patterns over a parallel fan-out:
  `majorityVote()` (deterministic first-encountered tie-break), `selectByMax { }`,
  `bestOfN { scorer }` (each output scored exactly once), `weighted(weights)` (missing agents
  default to 1.0). Pure sugar over `then`: builds a deterministic reducer agent named
  `aggregate-<strategy>`, so audit/streaming events carry the strategy name and the result is an
  ordinary `Pipeline<IN, OUT>`. All-branches-failed surfaces as the parallel stage's failure
  (`Failed` terminal on sessions). 6 tests.

### Added — `handoff` named operator (#3871, P1.1)

- **`triage handoff { on<BillingTask>() then billing; … }`** — the named hand-off primitive:
  identical routing semantics and sealed-exhaustiveness validation as `branch`, plus an audit
  contract — route selection fires the source agent's `onHandoff { toAgent, decisionInputType -> }`
  listener and `PipelineEvent.HandoffPerformed` (observe/JSONL/OTel/LangSmith/Langfuse), so
  reviewers can grep transfers specifically. Unlike OpenAI-Swarm-style handoff, the target never
  shares the source's conversation history — it receives only its declared input type; the
  single-placement rule holds across the transfer. Fires on both the blocking and streaming paths.

### Added — A2A protocol v1: server + typed client (#3864, P0.4)

- **`A2AServer.from(agent)`** exposes any `Agent<IN, OUT>` over A2A v0.2 (JSON-RPC over HTTP),
  following the McpServer precedent: JDK HttpServer, loopback-only bind, optional bearer auth.
  AgentCard at `/.well-known/agent-card.json` with `@Generable` input schemas; `message/send`
  maps the first text part to the agent's typed input and returns a completed Task whose
  artifact carries the output (JSON property map for typed OUT).
- **`a2aAgent<IN, OUT>(name, url)`** returns a real `Agent<IN, OUT>` handle for a remote A2A
  endpoint — drops into `then` / `/` / `forum` / `branch` and skill allowlists like a local
  agent. Remote JSON-RPC errors throw with the remote message; auth/HTTP failures fail loud.
- v1 scope: `message/send` only — streaming, task lifecycle, and `traceparent` propagation are
  tracked follow-ups (#3864 / #3873). New `docs/a2a.md`; README limitation bullet replaced.
  6 in-process round-trip tests (String + `@Generable` both directions, card, auth, errors).

### Added — history compression (#3865 Phase 1, P0.3)

- **`agent { historyCompression { … } }`** — before-turn compression for long-running agents:
  when the history exceeds `triggerMessages` (default 40; custom `triggerWhen { }` supported), the
  conversation middle collapses into one deterministic digest message. Leading system messages are
  pinned, the most recent `preserveRecent` messages stay untouched, and the preserved window
  extends backward so a tool result is never orphaned from its `tool_call`. Rides the
  `onBeforeTurn` → `Decision.ProceedWith` seam, so the loop history shrinks permanently.
- **Degrade-don't-fail:** a summarizer exception skips compression for that turn. Default
  summarizer is extractive and deterministic (no LLM call); pass `summarizer { }` for abstractive.
- **Observability:** `onHistoryCompressed { }`, `PipelineEvent.HistoryCompressed` (counts only —
  no conversation content in audit rows), JSONL audit rows, and OTel / LangSmith / Langfuse
  bridge events. 6 new tests incl. a mid-run agentic-loop integration.
- Phases 2 (tiered MemoryBank) and 3 (episodic/semantic split) tracked separately.

### Added — RAG seam: EmbeddingStore SPI + query-aware knowledge (#3863, P0.2)

- **Core knowledge seam:** `skill { knowledge(key, description, retriever) }` registers a
  query-aware `KnowledgeRetriever` — surfaced to the model as a knowledge tool taking a `query`
  argument (suspend on the session path, blocking-bridged otherwise), never inlined into the
  prompt. Static `knowledge(key) { content }` entries are unchanged.
- **New `:agents-kt-rag` module (in-repo):** minimal SPI — `EmbeddingStore<T>` (`upsert` /
  `query`), `Embedder`, `RagQuery` (text + optional embedding), `Match` with
  `Provenance { chunkId, sourceUri, hash }`, metadata `Filter` — plus a cosine
  `InMemoryEmbeddingStore` and `ragRetriever(store, embedder) { topK; minScore; filter { } }`
  bridging any store into the skill DSL with provenance-carrying rendered results.
- **Adapter modules (in-repo):** `:agents-kt-rag-langchain4j` (wraps LangChain4j
  `EmbeddingStore<TextSegment>`, 1.16.x) and `:agents-kt-rag-spring-ai` (wraps Spring AI
  `VectorStore`, 1.1.x — embeds internally, no `Embedder` needed). Both translate store metadata
  into `Provenance` and apply `Filter`s client-side.
- Out of scope by design: embedding models, vector-DB lifecycle, re-ranking/hybrid search.
  New `docs/rag.md`; comparison.md vector-store row updated. 14 new tests across the four modules.

### Added — streaming flows through every composition operator (#3866, P0.1)

- **Every `then` overload now chains streaming.** Pipelines that mix `Parallel` / `Forum` / `Loop` /
  `Branch` mid-chain (`a then (b / c)`, `(a / b) then reduce`, `head then forum`,
  `head then judge.loop { … }`, `head then classifier.branch { … }`) stream inner events from all
  nested agents through the parent `session(input)`, each tagged with its own `agentId`. Previously
  only `Agent then Agent`, `Pipeline then Agent`, `Pipeline then Pipeline`, and `wrap` streamed —
  the other 14 overloads fell back to terminal-only.
- **Internal emitter-aware `sessionInvoke` cores** on `Parallel` / `Forum` / `Loop` / `Branch`,
  extracted from (and now shared with) their `session(...)` extensions — one streaming
  implementation per operator instead of extension-local copies.
- Sequential stages emit in chain order; fan-out participants interleave by arrival order
  (demultiplex by `agentId`). Cancellation tears down in-flight inner sessions via structured
  concurrency. Operators constructed outside their factory functions still fall back to
  non-streaming execution. Behavior pinned in `CompositionStreamingChainTest` (6 scenarios).

### Changed — truth-surface pass 3 (June-12 delta review)

- **threat-model.md opening paragraph** no longer claims "does not sandbox tool execution" /
  "does not validate MCP request origins by default" — both contradicted the canonical table
  below it; rewritten to the precise lambda-vs-subprocess and loopback-default reality.
- **tool-policy-enforcement.md**: the stale "Layer 2 will extend enforcement" closing line names
  the shipped Layer 2 and the actual remaining 0.8 work; new high-level-vs-low-level warning box
  (`processTool` fail-closed vs raw `ProcessSandbox.run` warn-and-run).
- **model-and-tools.md** ToolPolicy section reframed from "declarative only in the 0.6.x line"
  to the 0.7 enforcement reality (+ the #2889 `ToolEnvironment` executor shape).
- **caching.md** provider framing fixed: Kimi/OpenRouter/Perplexity are first-party providers
  inheriting the OpenAI rows, not "fourth-party deployments"; `ModelProvider.entries` count
  corrected.
- **roadmap.md**: threat-model guide marked shipped; the demos bullet's never-shipped `Escalate`
  decision replaced with the real HITL primitives (#2489 / #3868).
- **`DocsConsistencyTest`** stale-phrase guard extended with the three newly-fixed claims.

### Changed — truth-surface pass 2: the rooms the front door missed

- **`docs/threat-model.md` is now the canonical "what's enforced where" page.** Its shipped-vs-planned
  table was frozen at 0.5.0 — ToolPolicy enforcement, MCP auth/origin/per-client policy, and the OS
  sandbox all listed as planned long after shipping. Rewritten as a Boundary / Status / Enforced-by
  table current to 0.7.24; README, `SECURITY.md`, and `production-hardening.md` now defer to it.
  Anti-pattern guidance points at fail-closed `processTool` (#2914); the JSONL-retention bullet
  reflects the shipped exporter (#1914) + tamper-evident ledger.
- **README security sections** no longer claim `ToolPolicy` is "for review/audit" only or that tool
  sandboxing is "on the Phase 3 roadmap" — replaced with the enforced-vs-yours split and a link to
  the canonical table. Kotlin badge 2.3 → 2.4. Streaming bullet covers all seven providers.
- **`docs/streaming.md`** provider table covers all seven providers (3 native `chatStream`
  implementations + 4 inherited OpenAI-compatible SSE, Perplexity live-verified); the composition
  flow-through gap is framed as a current 0.7.24 limitation instead of "the next v0.5.0 milestone".
- **`DocsConsistencyTest` gains a stale-phrase guard:** known-fixed claims ("sandboxing isn't
  shipped", "no tool sandboxing", "audit evidence, not", "all three first-party", stale
  four/five/six provider counts, "the JSONL exporter lands") now fail `./gradlew test` if they
  resurface in living docs; historical docs (CHANGELOG / RELEASE_NOTES / premortems / prd) exempt.

## [0.7.24] — 2026-06-12

**Perplexity: seventh model provider + web-grounded search with citations — and a truth-surface pass.** Headline feature is the Perplexity connector and the `perplexitySearch` tool — agents can now fetch live, cited facts from Perplexity Sonar against their own model. The release also lands the docs/version-identity trust patch an external 0.7.23 review called for: SECURITY.md, production-hardening, skill-routing and HITL docs catch up with the shipped runtime, `main` adopts a `-SNAPSHOT` between-releases policy, and a new `DocsConsistencyTest` keeps the claims pinned. Plus dependency bumps (Kotlin 2.4.0, jline 4, detekt 1.23.8, ksp 2.3.9). Drop-in on the 0.7.x line.

### Added — Perplexity connector + web-grounded search tooling (epic #3674)

- **`PerplexityClient` — seventh model provider (#3675).** A thin OpenAI-compatible `OpenAiClient`
  subclass for `api.perplexity.ai` (mirrors `DeepSeekClient` / `KimiClient` / `OpenRouterClient`),
  selectable via `model { perplexity("sonar") }`. Model ids: `sonar` / `sonar-pro` /
  `sonar-reasoning-pro` / `sonar-deep-research`. Unlike Kimi/DeepSeek, Perplexity accepts OpenAI's
  `response_format` json_schema, so its constrained-decoding gate stays **on**. `ModelProvider`,
  `ModelConfig.perplexityBaseUrl`, `ModelBuilder.perplexity(...)`, the factory dispatch, and the
  permission manifest are all wired. Key from `.secrets/perplexity-key` / `PERPLEXITY_API_KEY`.
- **`perplexitySearch` tool — web-grounded search with citations (#3676).** `tools { +perplexitySearchTool(key) }`
  lets an agent on its **own** model fetch live, cited facts from Perplexity. `untrustedOutput = true`,
  so results are wrapped in the `{"trusted":false}` envelope and flagged as data, not instructions
  (#642). The result renders the answer + a numbered source list parsed from `search_results[]`
  (falling back to `citations[]`); sources reach both the model context and the JSONL audit row.
- **Search controls + structured output (#3677).** `perplexitySearchOptions { }` maps to the documented
  request params: `search_mode` (web/academic/sec), `search_recency_filter`, `search_domain_filter`
  (allow + `-`-prefixed deny), `web_search_options.search_context_size`, `reasoning_effort`, and native
  `response_format` json_schema via `structuredOutput(MyType::class)` from a `@Generable` type.
- **`OpenAiClient` gains a `chatCompletionsPath` seam (#3675).** The chat-completions path is now
  overridable (default `/v1/chat/completions`); `PerplexityClient` overrides it to `/chat/completions`
  (Perplexity serves no `/v1` segment — hitting `/v1` there 404s with an empty body). Behavior is
  unchanged for OpenAI / DeepSeek / Kimi / OpenRouter.
- Additive only — no public-API change to existing surfaces. Verified end-to-end against the live
  Perplexity API (connector chat + streaming, and `perplexitySearch` with real citations); live tests
  tagged `live-cloud-api`.

### Changed — truth-surface pass: docs catch up with the shipped runtime

- **`main` now carries a `-SNAPSHOT` version between releases** (`0.7.24-SNAPSHOT`). Post-release
  commits no longer masquerade under the published version's identity. `checkReadmeVersion` learned
  the dev state: on a `-SNAPSHOT` version the README must advertise a plain release strictly below
  the snapshot base; exact lockstep still enforced at release (runbook step 8 added).
- **`SECURITY.md` rewritten to 0.7.x reality:** seven providers over four wire shapes (was "four
  adapters"); tool sandboxing and `McpServer` authentication are no longer "out of scope" — the
  Layer-1 in-JVM filesystem gate (#2890), Layer-2 OS sandbox (#1916), and
  `McpServerAuth.TrustedLocal`/`RequireBearerToken` are documented with the honest remaining-gaps
  list (in-JVM lambda side effects, read confinement, hostname allowlist deferred to 0.8).
- **`docs/production-hardening.md`** no longer calls `ToolPolicy` "audit evidence, not enforcement"
  (stale since 0.7.0) and now points subprocess tools at the fail-closed `processTool` (#2914).
- **Skill-routing docs match the fail-loud runtime (#3087):** `docs/model-and-tools.md` and the wiki
  routing pages documented the pre-0.7.21 silent first-match fallback; ambiguity now documented as
  `SkillRoutingException`, with a migration note.
- **Provider counts unified at seven** (Perplexity joined in this unreleased line): `model-and-tools.md`
  (was "six"), `SECURITY.md` (was "four"), `comparison.md` (was "4").
- **`docs/permission-manifest.md`** stops advertising `ai.deep-code:agents-kt-manifest` Maven
  coordinates — the module has never been published to Central (only `agents-kt` and `agents-kt-ksp`
  are); shown as an in-repo `project(":agents-kt-manifest")` dependency with the publication status
  stated. `comparison.md` maturity claims move from the 0.5/0.6 era to 0.7.23.
- **`docs/regulated-deployment.md` HITL section** documents the shipped primitives —
  `humanApproval { }` → `ApprovalRequest` → `resumeWith(HumanDecision)` (#2489) and the `onBefore*`
  interceptor decisions (#1907) — instead of the never-shipped `Decision.Confirm`.
- **New `DocsConsistencyTest`** pins provider-count sentences to `ModelProvider.entries`, doc
  `Decision.X` references to the real sealed variants, and the routing table to
  `SkillRoutingException` — docs drift in these spots now fails `./gradlew test`.

## [0.7.23] — 2026-06-04

**Maintainability + an explicit model-error policy.** Closes the bulk of the code-smell remediation
epic (#2790), finishes the `AgenticLoop` decomposition begun in 0.7.21, and makes the model-error
contract explicit with a new `onLLMError` recovery hook. The maintainability changes are all
behavior-preserving (no public-API change); `onLLMError` is the one additive public API. Over the
line, the detekt-baseline ratchet fell **423 → 415** and the main-module `@Suppress("UNCHECKED_CAST")`
count **42 → 30**. Drop-in on the 0.7.x line.

### Added — `onLLMError` model-failure policy + recovery hook (#3508)

- Makes the model-error contract explicit: when a model is configured, a failed model call in the
  agentic loop (a down provider — surfacing as the raw transport error like `ConnectException` — a
  5xx, or a malformed response) **fails fast and loud by default**. New `agent.onLLMError { e ->
  LlmErrorDecision }` opts into recovery: `RespondWith(fallback)` uses a canned/typed value (routed
  through the agent's `castOut`) instead of throwing; `Rethrow` (the default with no handler) keeps it
  loud. The handler receives the **original** exception — identity is preserved (the internal
  `LlmCallFailure` marker that makes a model failure recognizable is unwrapped at the loop boundary,
  so `onError` observers and `assertThrows` still see the real error). Does not fire for budget caps
  (`onBudgetExceeded`) or cancellation. With **no model** configured, `implementedBy` skills run
  deterministically and no model error can arise. Recovery is scoped to the agentic loop in this
  release; a model failure during multi-skill LLM routing still propagates loud (follow-up).

### Changed — Decompose the `Agent` god class: `InterceptorChain` + `ListenerRegistry` (#2793)

- The before-interceptor subsystem (the three interceptor lists, `onInterceptorDecision` observers,
  and decision plumbing) moves into an `InterceptorChain` collaborator; `decideBeforeToolCall`'s
  hand-inlined fold now reuses `runDecisionChain` (dropping the duplicated fold and its second
  `@Suppress("UNCHECKED_CAST")`). The ~11 observability listener slots + the token-usage / agent-event
  streams + their `fire*` dispatch move into a `ListenerRegistry`. The three copy-pasted
  fire-listener-swallow-and-log blocks collapse into one shared `dispatchSafely`. `Agent` keeps its
  public `onX` DSL setters and the `agent.<slot>` reads (forwarding to the collaborators) — no
  public-API change. `Agent.kt` is back to its structural graph + resolution config.

### Changed — Split the `McpServer` god class into HTTP transport + `McpDispatcher` (#2795)

- The transport-agnostic JSON-RPC protocol core (the `when(method)` routing + every per-method
  handler) extracts into `McpDispatcher`, operating purely on `Map → String` envelopes. `McpServer`
  keeps HTTP intake only; `handle()` slims to `authenticate → validateAllowedHost/Origin →
  validateRequest → readBoundedBody → dispatcher.dispatchRequest`, with the new `validateRequest` /
  `readBoundedBody` helpers. `McpStdioServer` drives the dispatcher directly via `dispatchEnvelope`,
  removing the `internal dispatchJsonRpc` back door. `McpServer.kt` 451 → 215; no public-API change.

### Changed — Extract `agentSessionScope`; remove operator session-extension duplication (#2797)

- The five composition operators (`branch` / `forum` / `loop` / `parallel` / `pipeline`) each
  repeated an identical ~25-line streaming-session scaffold (channel + deferred + supervisor scope +
  runtime context + context-threading emitter + terminal `Completed`/`Failed` + cancellation
  ordering). One `agentSessionScope(terminalAgentId, body)` now owns the lifecycle; the operators
  reduce to their run lambda (net −282 lines). The five emitter casts collapse to one; terminal events
  unify to never-drop suspending `send`; the per-operator drop-loggers collapse to one.

### Changed — Split the `LiveShow` god file: `LiveShowBanner` + `SpinnerAnimation` (#2798)

- The ASCII banner asset moves to `LiveShowBanner.kt`. The in-place inference spinner — previously a
  manual `Thread` + an `AtomicBoolean running` that *shadowed* the `LiveShow.running` field — becomes
  an `AutoCloseable` `SpinnerAnimation` so the turn handler reads `spinner.use { … }`; the shadowing
  flag is gone. CLI behavior unchanged.

### Changed — One deliberation/match core for `Forum` and `Branch` (#2802)

- `Branch.invokeSuspend` re-derived the ordered-match loop that `matchRoute` already implements; it
  now delegates, and the dead empty `NullRoute` exhaustiveness arm becomes a real `false`
  classification. `Forum`'s deliberation body (participants → captain, mention firing, `forum_return`
  short-circuit) — written twice across `invokeSuspend` and the streaming `session` — extracts into one
  `deliberate(input, runAgent)` core differing only in the run strategy; `participants` / `captain`
  are now properties.

### Changed — Typed `GenerableCodec` seam to shrink `UNCHECKED_CAST` clusters (#2803)

- `@Generable` deserialization gains a `GenerableCodec<T>` fun-interface and a single
  `KClass<T>.codec()` resolution boundary (KSP-generated decoder when present, else reflective).
  `constructFromMap` / `constructFromMapReflective` / `coerceValue` / `fromLlmOutput` route their casts
  through it, and the MCP edge (`ExposedSkill`) reuses the same seam — collapsing the casts that were
  sprinkled across the reflection and wire boundaries to one site. `GenerableSupport.kt` suppressions
  8 → 2. The tuned reflection edge-cases (sealed dispatch, coercion, strict keys) are unchanged.

### Changed — AgenticLoop: extract `resolveAllowedTools` (#3423)

- The last self-contained setup block in `executeAgentic` — the per-skill tool-set assembly (skill +
  agent-capability + memory tools, lazy knowledge tools, duplicate-name fail-fast, the authorization
  allowlist) — extracts into `resolveAllowedTools` returning a `ResolvedTools` bundle. `AgenticLoop.kt`
  754 → 722; the remaining `executeAgentic` body is the turn loop itself.

### Changed — AgenticLoop setup extraction: `SkillRouting` + `buildSystemPrompt` (#3406)

- Follow-up to #3376. Relocated `selectSkillByLlm` (LLM skill router) out of `AgenticLoop.kt` into
  its own `SkillRouting.kt` (same package — no FQN change for its `SkillResolver` caller); it's a
  routing concern, not a loop one. Extracted the inline system-prompt `buildString` into a pure,
  unit-tested `buildSystemPrompt` (`SystemPrompt.kt`) — `SystemPromptTest` pins the tool listing and
  the untrusted-tools security preamble (present iff a tool declares `untrustedOutput`).
  Behavior-preserving; `AgenticLoop.kt` 834 → 758 (1369 → 758 since #3376 began).

### Changed — AgenticLoop: collapse the executeAgentic signature into `RunRequest` (#3376 batch 5)

- `executeAgentic`'s accreted 11-parameter signature is folded into a single `RunRequest` (reusing
  the value object from #3088): the six per-invocation knobs (prompt override, resume/HITL state,
  checkpoint callback, manifest-mismatch opt-out, attachments) become `request: RunRequest`, leaving
  `(agent, skill, input, request, emitter, runtimeContext)`. The body is unchanged — the request is
  unpacked into the same locals at the top. `Agent.invokeSuspendForSession` now passes its `RunRequest`
  straight through instead of unpacking it. Internal API; behavior-preserving (the existing
  resume/snapshot/memory test suites are the net). Caps the #3376 decomposition: `AgenticLoop.kt`
  1369 → 834 across batches 1–5, with rendering / coercion / client-factory / tool-execution /
  snapshot-restore each extracted into their own unit-tested file.

### Changed — AgenticLoop decomposition: extract `restoreFromSnapshot` (#3376 batch 4)

- Pulled the resume/HITL restore step out of `executeAgentic`'s inline `if (resumeFrom != null)`
  block into `SnapshotRestore.kt` (`restoreFromSnapshot`): the manifest-hash fail-closed guard
  (#2754), namespaced memory restore (#2755), and the HITL interrupt-reply synthesis (#2488/#2489).
  The loop now delegates a one-liner. Previously only reachable through a full resuming invocation;
  now directly unit-tested (`SnapshotRestoreTest` — manifest-mismatch fail-closed + message restore).
  Behavior-preserving; `AgenticLoop.kt` 925 → 865.

### Changed — AgenticLoop decomposition: extract the tool-execution subsystem (#3376 batch 3)

- Moved the per-tool-call execution cluster out of `AgenticLoop` into a new `ToolInvoker.kt`: the
  budget gate (arg-size cap + per-tool timeout), the 4-layer recovery ladder (`executeToolWithBudget`
  / `executeToolWithRecovery` / `validateTypedArgsOrNull` / `recoverInvalidArguments` /
  `executeToolWithExecutionRecovery`), `toolArgsByteSize`, and the `ToolCallFinished` emit. These were
  `private` to the loop (only reachable through a full agentic invocation); now `internal` and
  directly unit-tested (`ToolInvokerTest`). The loop's thin event-wrapper delegates here.
  Behavior-preserving move; `AgenticLoop.kt` 1196 → 930 (1369 → 930 across batches 1–3).

### Changed — AgenticLoop decomposition: extract `ModelClientFactory` (#3376 batch 2)

- Moved the provider/client-construction cluster out of `AgenticLoop` into a new `internal`
  `ModelClientFactory` — `defaultClientFor` (the per-provider dispatch), `defaultClientForTesting`
  (the #2385 seam), `semconvProviderName`, and `constrainedOutputSchemaFor`. The first three were
  `private` (only `defaultClientForTesting` was reachable); they now have a direct unit test
  (`ModelClientFactoryTest`, TDD RED→GREEN). `AgenticLoop` delegates; behavior-preserving, no public
  API change.

## [0.7.21] — 2026-06-02

**Security + de-slop release.** Headlined by a nested-agent recursion bound (#3377) and the explicit
skill-routing failure on ambiguity (#3087), plus a build-wide one-type-per-file refactor (#3199) and
new release/quality guards (#3084 / #3089), the start of the AgenticLoop decomposition (#3376), and
honest README positioning (#3085 / #3086). Internal refactors are behavior-preserving; the two
behavior changes (routing, the `maxAgentDepth` default) are called out below. Drop-in on the 0.7.x line.

### Fixed — bound nested agent recursion with `maxAgentDepth` (#3377, security)

- Budgets bounded a single agentic loop, but a tool that re-invokes an agent (Swarm `absorb`,
  agent-as-tool) spun up a fresh loop with a fresh budget — so a self-re-entering agent (A→A) or a
  cycle (A→B→A) recursed one full LLM loop per level until `StackOverflowError`, a DoS / runaway-cost
  vector (triggerable e.g. by prompt injection into a tool result). Now `AgentRuntimeContext` carries
  a nested-invocation `depth` (incremented in `newRuntimeContext`), and `budget { maxAgentDepth }`
  (default **16**) is enforced at the invocation chokepoint: exceeding it throws
  `BudgetExceededException(BudgetReason.AGENT_DEPTH)` **before** the over-deep loop starts — fast, no
  extra LLM calls, no overflow. An unconditional safety stop (not extendable via `onBudgetExceeded`),
  and budget caps now bypass the `onError` tool-recovery ladder so a nested cap can't be swallowed.

### Changed — AgenticLoop decomposition: extract rendering + coercion (#3376 batch 1)

- First slice of breaking up the 1369-line `AgenticLoop.kt` / 765-line `executeAgentic`. Extracted
  the pure tool-result/error renderers into `ToolResultRendering` (`formatEscalatedToolError`,
  `formatDeniedToolError`, `wrapUntrustedToolResult`, `renderToolResultForLlm`) and output coercion
  into `OutputCoercion` (`parseOutput`, `coerceSubstituteOutput`) — each a new `internal` file. These
  were `private` to the loop (untestable); they now have direct unit tests (`ToolResultRenderingTest`,
  `OutputCoercionTest`, TDD RED→GREEN). Behavior-preserving — `AgenticLoop` delegates. Internal
  refactor, no public API change.

### Changed — one-type-per-file complete across the codebase (#3199, final batch)

- Split every remaining multi-type file (rest of `model/`, all of `core/`, `content/`, `composition/`,
  `generation/`, `runtime/`, `sandbox/`, `testing/`, and the `manifest` / `observability` / `langfuse`
  / `langsmith` / `detekt` submodules) into one top-level type per file — ~110 new files, all
  same-package moves (no FQN / public-API change). **`checkOneTypePerFile` now passes with an empty
  allowlist: zero multi-type files remain anywhere.** Renamed 3 files so the filename matches the
  kept type (`Snapshot.kt`→`SessionSnapshot.kt`, `Memory.kt`→`MemoryBank.kt`,
  `HumanApproval.kt`→`ApprovalBuilder.kt`), which also satisfies detekt's `MatchingDeclarationName`.
- Minor, non-public visibility consequence of the moves: a handful of file-`private` helpers that
  were referenced across now-separate files were promoted to `internal` (still module-scoped, not
  public): the manifest engines (`ManifestVerifier`/`StableJson`/`ManifestJsonParser`/`StableYaml`/
  `ManifestGraph`), the policy JSON/YAML helpers (`ManifestMaps`/`ManifestJson`/`ManifestYaml`),
  `RuntimeContextThreadLocal`, `KnowledgeEntry`, and the `nonBlank` helper.
- Behavior-preserving: full `./gradlew build` green (all modules + all tests + detekt 423/423 +
  `checkOneTypePerFile` 0 + `checkReadmeVersion`). Completes #3199.

### Changed — one-type-per-file: split model error/cache types (#3199, batch 3)

- Split three `agents_engine.model` files into one type per file (same package — no FQN/public-API
  change): `ToolError.kt` → `Severity`, `EscalationException`, `ToolExecutionException` (`ToolError`
  sealed union stays); `CacheHint.kt` → `CacheSegment` (`CacheHint` stays); `OnErrorBuilder.kt` →
  `RepairResult`, `RepairScope`, `ToolErrorHandler` (`OnErrorBuilder` + the `executeAgentFix` helper
  stay). Allowlist 40 → 37. Behavior-preserving pure moves; detekt baseline unchanged.

### Changed — one-type-per-file: split `McpServer.kt` (#3199, batch 2b)

- Split the four secondary types out of `mcp/McpServer.kt` (same package) — `RegisteredPrompt`,
  `RegisteredResource`, `McpExposeBuilder`, `ExposedSkill` → one file each; `McpServer` stays
  (597 → 454 lines). Four now-unused imports (`constructFromMap`, `jsonSchema`, `KClass`,
  `hasGenerableAnnotation`, all moved to `ExposedSkill`) removed. Completes `mcp/` — allowlist
  41 → 40. Behavior-preserving pure moves.

### Changed — one-type-per-file: split the `mcp/` package (#3199, batch 2)

- Split five multi-type files in `agents_engine.mcp` into one type per file (same package — zero
  import churn, no FQN/public-API change): `AgentMcpDsl.kt` → `McpServerBuilder.kt`; `JsonRpc.kt` →
  `JsonRpcWire` / `JsonRpcErrorCode` / `McpException` (+ `JsonRpc` stays); `McpClient.kt` →
  `McpToolDescriptor`; `McpRunner.kt` → `RunnerConfig` + `McpRunnerBuilder`; `McpServerSecurity.kt` →
  `ClientPrincipal` / `McpHttpRequestContext` / `McpAuthDecision` / `McpServerAuth` (original file
  removed). Allowlist 46 → 41. `mcp/McpServer.kt` (597 lines, `ExposedSkill` needs import surgery)
  is deferred to batch 2b. Behavior-preserving pure moves.

### Changed — one-type-per-file convention + `checkOneTypePerFile` guard (#3199, batch 1)

- New `checkOneTypePerFile` Gradle guard (wired into `check`) fails the build if a main-source `.kt`
  file declares >1 top-level type and isn't on `config/one-type-per-file-allowlist.txt`. The allowlist
  is a ratchet that may only shrink — it also fails on a stale entry (a listed file that no longer
  violates), so a split must record its own burndown. Documented sealed-ADT exceptions stay listed.
  Mirrors `checkReadmeVersion` / `checkDetektBaseline`.
- Batch 1 split: `mcp/McpServerInfo.kt` (12 MCP wire DTOs) → one type per file in the same
  `agents_engine.mcp` package — zero import churn, no FQN/public-API change. New `docs/source-layout.md`
  documents the convention, exceptions, and the guard. Remaining multi-type files burn down
  package-by-package in follow-up batches under #3199.

### Changed — skill resolution extracted into `SkillResolver` (#3088 stage 2, de-slop #3083)

- The skill-resolution cluster — type-compatible candidate filter, manual `skillSelection { }`
  selector, LLM router (confidence gate), the before-skill-interceptor `ProceedWith` compatibility
  check, and the fail-loud ambiguity error — moved out of `Agent`'s God-object body into its own
  `SkillResolver` collaborator (new `SkillResolver.kt`). `Agent` keeps a `private val skillResolver`
  and delegates. **Internal refactor, behavior-preserving** — every branch, condition, exception
  type, and message is identical; no public DSL change. `Agent.kt` is now 1017 lines (1116 → 1017
  across #3088 stages 1+2). Completes the staged decomposition of #3088.

### Changed — README de-slop: honest positioning + accuracy fixes (#3085, #3086, de-slop #3083)

- Replaced the unqualified hero copy ("The auditable Kotlin agent runtime for regulated teams") with
  a defensible positioning line ("The typed agent runtime for the JVM") plus an up-front pointer to
  the Security Model and threat model, and an explicit "not a compliance product / does not OS-sandbox
  arbitrary tool code" caveat in the intro. The honest enforce/don't-enforce tables already existed;
  the hero no longer contradicts them (#3085).
- Fixed accuracy drift between "Implemented today" and the limitations/roadmap (#3086): "Four LLM
  providers shipped" → six (adds Kimi + OpenRouter); "Text-only I/O today" → image/document input
  shipped, audio + generation still roadmap; Kotlin badge `2.1` → `2.3`; Phase 2 roadmap no longer
  lists already-shipped image multimodal as planned. No fabricated benchmark claims were found.

### Added — explicit `securityCheck` gate, `checkDetektBaseline` burndown, and TESTING.md (#3089, de-slop #3083)

- New **`securityCheck`** aggregate task makes the deterministic security suite addressable on its
  own — sandbox write-confinement (`ProcessSandbox`: Seatbelt / bwrap / firejail / fallback), tool-
  policy enforcement (#1916), snapshot manifest guard, the arg-size cap (#2888), the tamper-evident
  audit ledger (`:agents-kt-observability:securityTest`), and the static tool-body rules
  (`:agents-kt-detekt:test` + `detekt`). OS-specific confinement skips cleanly off-platform; run
  `securityCheck` on a macOS job to exercise Seatbelt in CI.
- New **`checkDetektBaseline`** task (wired into `check`) fails if `detekt-baseline.xml` grows beyond
  the recorded ceiling (424) — the baseline may only shrink, so new violations get fixed rather than
  grandfathered.
- New **[`TESTING.md`](TESTING.md)** documents, honestly, what the default gate runs and excludes
  (`live-llm` / `live-mcp` / `interactive` are out; `live-cloud-api` is deliberately in), the
  security gate, the OS-specific confinement matrix, and the baseline ratchet.
### Added — `checkPublishedVersion` release gate + release runbook (#3084, de-slop #3083)

- New `checkPublishedVersion` Gradle task HEADs Maven Central for `ai.deep-code:agents-kt` **and**
  `agents-kt-ksp` at the current project version and fails unless both resolve (HTTP 200). It is
  **not** wired into `check` — it needs network and would (correctly) fail on an unreleased version
  during dev — so it's the manual last gate before anything user-facing names a new version.
  Override the base URL with `-PcentralBaseUrl=…`. Complements `checkReadmeVersion` (#2873): one
  stops the README drifting from the build, the other stops the build advertising a version Central
  can't serve — the exact drift (README/Gradle at `0.7.2` while Central served `0.7.1`) an external
  review flagged.
- New [`docs/RELEASE_RUNBOOK.md`](docs/RELEASE_RUNBOOK.md) pins the release ordering
  (bump → build → bundle → upload → **confirm resolvable** → only then advertise/tag).

### Changed — invocation parameters bundled into `RunRequest` (#3088 stage 1, de-slop #3083)

- The internal `Agent.invokeSuspendForSession` entry point no longer carries an accreted list of
  optional knobs (prompt override, `resumeFrom` / `resumeWith` / `onTurnCheckpoint` /
  `allowManifestMismatch`, attachments). They're bundled into a single `RunRequest` value object
  (new `RunRequest.kt`); each field defaults to a fresh invocation, so `invokeSuspend` and the
  non-streaming path are byte-for-byte unchanged. **Internal API**, behavior-preserving — no public
  DSL or `agent { }` surface change. First stage of the staged `Agent.kt` decomposition; collaborator
  extraction (skill resolution, resume/HITL state) is tracked as later stages of #3088.

### Changed — skill routing is now explicit: ambiguous candidates fail loud (#3087, de-slop #3083)

- When an agent has **multiple compatible skills** for an output type and **no** `skillSelection { }`
  selector and **no** `model { }` for LLM routing, invocation now throws `SkillRoutingException`
  naming the ambiguous candidates and how to disambiguate — instead of silently routing to the
  first skill by registration order. An "auditable / explicit boundaries" runtime must not pick a
  production route implicitly. **Behavior change:** code that relied on silent first-match must add
  an explicit selector or a model. Single-candidate, selector, and model-routed paths are unchanged.
## [0.7.2] — 2026-06-01

**Tool-security hardening** — the self-contained first phase of the capability-ABI epic (#2882),
all additive and back-compat: a tamper-evident audit ledger, an argument-size cap, and the
static tool-body guard rails. Plus a release guard so the README's advertised version can't drift
from the build.

### Added — release guard: README dependency version must match the Gradle version (#2873)

- New `checkReadmeVersion` task (wired into `check`) fails the build if the
  `ai.deep-code:agents-kt:<version>` snippet in `README.md` differs from the Gradle project
  version — the exact drift an external 0.7.0 review flagged. README and version now move together.

### Added — `ToolCapabilityExtractor`: static capability classification (#2884, epic #2882)

- New `ToolCapabilityExtractor` in `agents-kt-detekt` statically classifies what a tool's executor
  body actually does — `FS_READ` / `FS_WRITE` / `NETWORK` / `ENVIRONMENT` / `EXEC` — by walking its
  call expressions and matching callee names (`writeText`/`Files.write` → write, `readText`/
  `readAllBytes` → read, `URL`/`openConnection` → network, `getenv` → env, `ProcessBuilder`/`exec` →
  exec). The reusable input the upcoming `ToolPolicy`↔capability comparator (#2887) checks against the
  *declared* policy. Syntactic by design (callee-name match, no FQN resolution) and intentionally
  conservative — reflection / aliasing / transitive state are Pillar-3 residual.

### Added — `ToolAuditLedger`: tamper-evident, Merkle-chained tool-action log (#2886, epic #2882)

- New `ToolAuditLedger` (in `agents-kt-observability`, sibling to `JsonlAuditExporter`) — an
  **append-only, Merkle-chained, PII-safe** record of every tool action. Each row's
  `entryHash = SHA-256(prevHash ‖ sequence ‖ callId ‖ toolName ‖ decision ‖ denialReason ‖
  resultHash ‖ timestamp)` chains to the previous, so `ToolAuditLedger.verify(path)` recomputes the
  chain and pinpoints the first **edited / inserted / deleted / reordered** row. The tool result is
  stored only as a hash, never raw (Pillar 2 of #2882).
- Auto-wire with `agent.events.ledger(file)` — records `PipelineEvent.ToolCalled` as `APPROVED`,
  `ToolDenied` as `DENIED` (with reason), `ToolHallucinated` as `HALLUCINATED`, and returns the
  ledger for later `verify(...)`. (callId-keying of denied/hallucinated rows lands once
  `PipelineEvent` carries the callId — a scoped #2886 follow-up.)

### Added — `maxToolArgsBytes` tool-argument size cap (#2888, epic #2882)

- New `budget { maxToolArgsBytes = … }` (`Long?`, default `null` = off) hard-caps a single tool
  call's argument byte size, checked at one chokepoint (`executeToolWithBudget`) **before** the
  executor runs — so an oversized (often prompt-injected) call is rejected, not executed. Resource-
  exhaustion guard (attack A5). Unconditional like `perToolTimeout` — not extendable via
  `onBudgetExceeded`; surfaces as `BudgetExceededException(reason = BudgetReason.TOOL_ARGS_SIZE)`.
  Size is the provider wire form (`ToolCall.rawArguments`) when present, else the serialized arg map.
  Gates both the session and regular executor paths; back-compat (null = unbounded).

### Added — `agents-kt-detekt` rule module + `ToolBodyForbiddenApis` (#2885, epic #2882)

- New `:agents-kt-detekt` module ships custom detekt rules (Pillar 1 static layer). The first rule,
  **`ToolBodyForbiddenApis`**, flags raw outside-world APIs (`java.io.File`, `java.net.URL` /
  `HttpURLConnection`, `ProcessBuilder` / `Runtime.exec`, `Class.forName`, `Unsafe`, sockets) used
  **inside a tool `executor { }` body** — a tool must reach fs/net/env only through the (forthcoming)
  closed `ToolEnvironment` ABI, so every action is policy-gated and audited. Suppressible with
  `@Suppress("ToolBodyForbiddenApis")` + a reviewed reason. Wired into the project's own detekt run
  (scoped to main source — test fixtures legitimately exercise tools). Consumers opt in via
  `detektPlugins("ai.deep-code:agents-kt-detekt")`.
- Honest limit: syntactic (matches the callee name, not a resolved FQN) — reflection / aliasing /
  transitive state changes are residual risk covered by Pillar 3 (process isolation). The capability
  *extractor* (#2884) builds on this module next.

## [0.7.1] — 2026-05-31

Hardening release driven by external review of 0.7.0. The headline fix makes the manifest
`verify` gate honest; the rest corrects docs/KDoc that lagged the code. No behavior change beyond
the verifier (which now flags genuine widenings it previously missed).

### Fixed — manifest `verify` compares policy sets, not coarse scores (#1923 hardening)

- `ManifestVerifier` previously compared coarse per-tool scores (network `allowAll`=2 / `hosts`=1 /
  none=0; filesystem any-globs=1 / none=0), so real widenings slipped through: adding a host within
  `hosts` mode (`["api.internal"] → ["api.internal", "evil.example"]`) or broadening a write glob
  without changing the count (a narrow upload-folder glob → a root-level glob) were **not** flagged.
  It also keyed tools by name with `putIfAbsent`, so two agents with a same-named tool collided and
  one agent's widening was hidden.
- Now it compares the actual policy **sets**, keyed by `agentName.toolName`. Network widening = mode
  escalation (denyAll/unspecified → hosts → allowAll) or a host the baseline did not list;
  filesystem / environment widening = a glob / variable the baseline's set did not contain (new
  `tool.environment.widened` finding). Pure narrowing (removing entries) is not flagged; conservative
  on added entries — semantic glob-*coverage* subset-checking is a later refinement. Regression tests
  pin each previously-missed case. Both the CLI `verify` and the Gradle `verifyAgentManifest` inherit
  the fix. Surfaced by external review of 0.7.0.

### Fixed — docs/KDoc drift surfaced by review

- **Provider count:** `docs/model-and-tools.md` / `docs/providers.md` said four providers; six ship
  (`OLLAMA`, `ANTHROPIC`, `OPENAI`, `DEEPSEEK`, `KIMI`, `OPENROUTER`). Kimi (#2697) + OpenRouter
  (#2701) are first-party providers extending the OpenAI adapter.
- **Layer-2 KDoc:** `SandboxedTools.kt` no longer says "macOS only; Linux is #2892" — bwrap + firejail
  ship in 0.7.0; the runtime error now reads "no OS sandbox backend (need macOS sandbox-exec or Linux
  bwrap/firejail)", and `processTool` is documented as the fail-closed public path.
- **PUBLISHING.md** bundle example bumped off stale `0.5.0` paths.

## [0.7.0] — 2026-05-31

**Boundaries you can enforce externally.** The 0.6 line made tool policies *declarative* and
*auditable*; 0.7.0 makes them **enforced**. A tool's declared `ToolPolicy` now constrains it at
runtime — Layer 1 (in-JVM filesystem-argument gate, #2890) plus Layer 2 OS sandboxing (#1916):
**macOS Seatbelt**, **Linux bubblewrap**, a **firejail setuid fallback**, and a plain
`ProcessBuilder` + loud `UNCONFINED` warning where no tool is present. Subprocess-shaped tools are
confined to their declared write roots, a derived environment allow-list, a working directory, and a
**default-deny network**. And the deterministic permission manifest is now reachable **outside
Gradle** via the standalone **`agents-kt` CLI** (`generate` / `inspect` / `verify`) — a drop-in CI
gate that fails when a change widens a capability boundary.

Deferred to 0.8 (tracked, not shipped here): `WasmSandbox` (#2894), `DockerSandbox` (#2895), the
network **hostname-allowlist proxy** (#2893; default-deny ships, selective allow does not), and the
`grants { }` hierarchical structure DSL.

### Added — standalone `agents-kt` CLI: permission manifest from a binary (#1923)

- New `:agents-kt-cli` module (Gradle `application` plugin) — the **"externally"** half of
  the 0.7.0 arc. The deterministic permission manifest, previously reachable only through a
  Gradle task, is now generatable / inspectable / verifiable from a binary, so non-Gradle
  consumers (CI gates, ops, regulators) can enforce capability boundaries:
  - `agents-kt generate --entrypoint <FQN> [--classpath a:b] [--format json|yaml] [--out file]`
  - `agents-kt inspect <manifest.json> [--format json|yaml]`
  - `agents-kt verify (--entrypoint <FQN> [--classpath a:b] | --current <file>) --baseline <file>`
  - Exit codes: `0` ok · `1` verify findings (policy widened) · `2` usage · `3` runtime.
- The reflective entrypoint→manifest loader was extracted from the Gradle plugin into a
  Gradle-free `agents_engine.manifest.ManifestEntrypointLoader`, **shared** by the plugin and
  the CLI — a build and the CLI produce byte-identical manifests (same `manifestSha256`).
  `verify` raises the same `tool.risk.increased` / `tool.network.widened` /
  `tool.filesystem.write.widened` findings as the `verifyAgentManifest` Gradle task. See
  `docs/cli.md`. (A jlink/native single-file image is a packaging follow-up; the
  entrypoint-loading commands reflect into arbitrary user classes and need a real JVM.)

### Added — injectable `HttpClient` on every provider client (#2385)

- `model { httpClient = … }` lets multiple agents **share one networking surface** —
  a connection pool, a bounded executor that rate-limits concurrent LLM calls, an
  outbound proxy, or an `HttpClient` already wired to your telemetry. All four provider
  clients (`Ollama`/`Claude`/`OpenAI`/`DeepSeek`) take an optional `httpClient: HttpClient?`
  constructor param; `ModelConfig.httpClient` is threaded into each by `defaultClientFor()`
  (DeepSeek inherits it via its `OpenAiClient` superclass).
- **Opt-in, never automatic.** `null` (default) → each client builds its own, byte-for-byte
  unchanged. The framework provides the seam; the rate-limit/circuit-breaker/bulkhead policy
  lives in your injected client. See `docs/model-and-tools.md` → "Sharing a networking surface".

### Added — automatic in-JVM tool-policy enforcement (Layer 1 of #1916, #2890)

- A tool's *declared* `ToolPolicy` is now **enforced at runtime** by default. When a tool
  call carries an **absolute filesystem-path argument** that falls outside the tool's
  declared `read`/`write` globs, the call is denied before its executor runs — surfacing
  through the existing `onToolDenied` / `PipelineEvent.ToolDenied` audit path (with
  `toolPolicyRisk` + `usedDeclaredCapability`). No hand-written `onBeforeToolCall`
  interceptor is required anymore. Paths are normalized first, so `..` traversal cannot
  escape a declared glob.
- **Opt-in by declaration:** a tool that declares no filesystem stance
  (`filesystem` left `Unspecified`) is never gated — existing tools are unaffected.
- **Escape hatch:** `agent { enforceToolPolicies = false }` restores the prior 0.6.0
  declare-only (inert) behavior.
- **Scope (this is Layer 1):** in-JVM, filesystem-argument enforcement for in-process
  tools. Relative-path precision and `network`/`environment` isolation require the
  Layer 2 OS sandbox (`ProcessSandbox` / `WasmSandbox` / `DockerSandbox`, tracked under
  #1916). See `docs/tool-policy-enforcement.md`.
- This flips the `ToolPolicyEnforcementTest` 0.6.0-gap tripwire (#2395) from "restricted
  write still happens" to "restricted write is blocked."

### Added — Layer 2 OS sandbox, first slice: macOS write-confinement (#2906, under #2891)

- New `agents_engine.sandbox.ProcessSandbox` — runs a command under macOS **Seatbelt**
  (`sandbox-exec`) with a generated profile that denies by default and allows file
  **writes only under a single canonical folder**. A write to any path outside that
  folder is blocked by the **kernel**, not just the in-JVM Layer-1 gate — so it holds
  even for paths the tool constructs itself. `seatbeltProfile(root)` is a pure,
  unit-testable function; `isSupported()` is false off macOS and `run` throws there.
- New `sandboxedEchoToFileTool(folder)` — the simplest demonstration: a tool that echoes
  text into a given path, OS-confined to `folder`. In-folder writes succeed; out-of-folder
  writes return an `ERROR` and create no file.
- The sandbox now builds its profile from a tool's **declared `ToolPolicy`** (#2909):
  `ProcessSandbox.forPolicy(policy)` derives the writable roots from the `filesystem.write`
  globs (each glob's directory prefix via `globToWriteRoot`) and opens network only for
  `network = AllowAll`; `ProcessSandbox.forWritableRoots(roots)` confines writes to several
  folders at once. This is the bridge that lets Layer 1's declaration drive Layer 2's OS
  enforcement.
- `processTool(name, policy) { args -> command }` (#2914) auto-sandboxes a subprocess tool
  **from its declared policy** — no hand-wiring of `ProcessSandbox`. It returns the command's
  stdout on success (or an `ERROR:` string), carries the policy onto the `ToolDef` so Layer-1
  (#2890) gates path args too, and **fails closed** (refuses to run rather than executing
  unsandboxed) where no OS sandbox is available.
- **Linux backend (#2892)** — `ProcessSandbox` dispatches by OS at run time: macOS **Seatbelt**,
  Linux **bubblewrap** (`bwrap`), then Linux **firejail** (the setuid fallback). The Linux paths
  bind/mount the whole filesystem read-only, re-mount the declared write roots read-write, and drop
  the network unless opened — same write-confinement contract as Seatbelt, enforced by the kernel.
  firejail still confines where unprivileged user namespaces are restricted (e.g. Ubuntu 24.04's
  `apparmor_restrict_unprivileged_userns`) and `bwrap` can't start. On a host with **no** sandbox
  tool, `run` no longer throws — it runs the command via a plain `ProcessBuilder` and prints a loud
  `UNCONFINED` warning (`isSupported()` stays false, so a caller that requires enforcement can
  refuse). `isSupported()` is true when any backend is present, so `processTool` / `forPolicy` work
  across all three. The pure `bwrapArgs(...)` / `firejailArgs(...)` are unit-tested everywhere; the
  kernel-level integration (`@EnabledOnOs(OS.LINUX)` + `@Tag("linux_only")`) is verified on CI's
  native Ubuntu runner.
- **Subprocess env + cwd honored** (#2892): `ProcessSandbox` now confines the child's environment and
  working directory. `forPolicy` derives the env from the declared `ToolEnvironmentPolicy` —
  `environment { allow("HOME") }` passes only those vars through, `environment { denyAll() }` gives the
  child an empty environment, unspecified inherits; `forWritableRoots(..., env, workingDir)` sets them
  explicitly. Applied on the `ProcessBuilder`, so every backend (Seatbelt / bwrap / firejail) inherits
  the confinement.
- **Network default-deny** ships across all backends (#2893 core): only `network { allowAll() }` opens
  the network; `denyAll` / `Hosts` / unspecified stay blocked (Seatbelt no-network, bwrap
  `--unshare-net`, firejail `--net=none`). The hostname-allowlist **proxy** (so `Hosts` can selectively
  allow domains) remains the deferred part of #2893.
- Remaining Layer-2 follow-ups: the network hostname-allowlist **proxy** (#2893), read-confinement, the
  `grants { }` structure DSL, and the `process { }` DSL. Wasm/Docker backends are #2894/#2895.

### Errata — v0.6.5 release notes overstated document-attachment shipping (#2868)

- The v0.6.5 tagged `RELEASE_NOTES.md` carried an "Added — Document attachments (#2470 slice c)" section claiming PDF / text / markdown routing through Anthropic, OpenAI, Ollama, and DeepSeek. **That section was incorrect.** The shipped code in 0.6.5 (and 0.6.6) routes only `Content.Image` through `agent.invokeWithAttachments(...)`; `Content.Document` is explicitly skipped in `AgenticLoop` (`executeAgentic` filters non-Image variants with a `// Not an image — skip in v1` comment).
- What 0.6.5 actually shipped on the multimodal axis: `Content` hierarchy (#2466), `ContentRef` + `BlobStore` (#2467), `ToolResult` with audit placeholder rendering for every modality (#2469), **vision input wire path only** (#2470 slice a — Image), typed agent attachments for Image (#2470 slice b), `Files.load` extension-based modality detection across all content types (#Files).
- What's still deferred: `#2470 slice c` (Document/Audio/Video provider-input adapters). `docs/multimodal.md` has always said this — the drift was only in the tagged release-notes prose. The 0.6.6 `RELEASE_NOTES.md` does not repeat the false claim.

## [0.6.6] — 2026-05-30

### Fixed — Session catch swallowed CancellationException as AgentEvent.Failed (#2863)

- **All six session extensions** (`AgentSessionExtension`, `PipelineSessionExtension`, `ParallelSessionExtension`, `BranchSessionExtension`, `LoopSessionExtension`, `ForumSessionExtension`) — the outer `catch (t: Throwable)` block previously treated every `CancellationException` as a real failure: it emitted a synthetic `AgentEvent.Failed`, closed the channel cleanly, and swallowed the cancel from the surrounding scope. Field-reported regression (SSE bridge rendered "FlowSubscription was cancelled" as a user-visible failure, clobbering already-streamed partial output).
- Rewritten as ordered multi-catch: `TimeoutCancellationException` first (real failure → `Failed` path, must come before bare `CancellationException` because it's a subtype), then `CancellationException` (propagate per structured-concurrency contract — close channel with the cancel, rethrow), then `Throwable` (real failure → `Failed`).
- Pinned by new `SessionCancellationTest` — 2 structural cases (`bare cancellation propagates — no Failed event`, `executor failure still emits Failed`) plus 4 per-vendor cases (Ollama / Claude / OpenAI / DeepSeek) using stub `ModelClient` injections so a future adapter-specific regression can't slip past CI.

### Changed — Maintainability epic #2790 (10 refactor tickets)

A code-smell audit landed 10 focused refactors. All behavior-preserving; no public API removals.

- **#2806 — Runtime cleanup.** Central `agents_engine.runtime.Ansi` object owns `ESC` / `RESET` / `ERASE_LINE`; `AnsiColor.code` + `wrap` + spinner clear route through it; dead `AnsiColor.Companion.RESET` deleted. Session-extension bracket events (Completed/Failed) on Agent/Pipeline/Branch/Parallel switched from non-suspending `trySend` → suspending `send` so terminal events can't be dropped silently; inner per-token emitter stays on `trySend` (typealias is non-suspending) but now logs JUL warnings on failure. `agents_engine.internal.BuildInfo.version` reads `Implementation-Version` from the JAR manifest (stamped by `tasks.jar { manifest { ... } }`); `McpServer.SERVER_VERSION` / `McpClient.CLIENT_VERSION` / `McpRunner.VERSION` all forward to it — the three constants had drifted to `0.1.3 / 0.1.3 / 0.3.0`.
- **#2805 — Core/generation cleanup.** `enum class ToolRisk(val manifestName: String)`; `fromManifest` derives from `entries` instead of a duplicate when-block. `Agent.describeBudget()` reflection (`BudgetConfig::class.members`) replaced with `BudgetConfig.describeOverrides()` — restores reflect-optional contract (#1718). Broad catches in `GenerableSupport` / `LenientJsonParser` / `GeneratedMetaCache` FINE-logged via new `tryGenerable` helper; `GeneratedMetaCache.tryLoad` narrowed to `LinkageError / ReflectiveOperationException / SecurityException`. `ManifestYaml.parsePolicyMap` literal `0/2/4/6` indent levels replaced with named `DEPTH_TOPLEVEL/SECTION/LEAF/FILESYSTEM_LEAF` constants.
- **#2804 — Model-layer cleanup.** `AgenticLoop` reuses `RESERVED_MEMORY_TOOL_NAMES` (no parallel inline set). Named constants `MANIFEST_HASH_PREFIX_LEN=12`, `BLOB_HASH_PREFIX_LEN=12`, `ANTHROPIC_MAX_CACHE_BREAKPOINTS=4`, `EPHEMERAL_TTL_BOUNDARY_MINUTES=5L`. New `MutableList<ToolDef>.reserveName(name)` collapses 5× duplicated `require(...)` for "Tool already defined". `Severity.valueOf` bad parse logs at WARNING. 4 near-identical `AgentEvent.ToolCallFinished` emit blocks → `emitToolFinished(...)` helper; 5 inline `agents_engine.runtime.events.AgentEvent.…` FQNs removed.
- **#2799 — JSON escape consolidation.** `JsonEscape` moved to `agents_engine.internal` so generation + core can depend on it without inverting the model→generation direction. `generation.GenerableSupport.escapeJson`, `core.ToolPolicy.ManifestJson.quote`, `core.Snapshot` all flow through `toJsonString()` now. The repeated `{"type":"object","properties":{},"additionalProperties":true}` literal promoted to `internal.OPEN_EMPTY_OBJECT_SCHEMA_JSON`. `ClaudeClient` `removeSuffix("}") + ",$cc}"` cache-control surgery extracted to `appendCacheControlToBlock` / `appendCacheControlToLastBlock` helpers. New control-char regression test in `UntrustedToolOutputTest`.
- **#2796 — Shared JsonRpc helper for MCP.** New `agents_engine.mcp.JsonRpc` consolidates `encodeRequest/encodeResult/encodeError/parseEnvelope/isNotification`. `JsonRpcWire` owns the literal `"2.0"`, wire keys, and notification prefix. `JsonRpcErrorCode` names `-32700/-32600/-32601/-32602/-32603` as `PARSE_ERROR/INVALID_REQUEST/METHOD_NOT_FOUND/INVALID_PARAMS/INTERNAL_ERROR`. New `sealed class McpException : IllegalStateException` (extends ISE for back-compat) with `Transport / Protocol / ToolFailure` subclasses.
- **#2792 — Shared HttpModelClientSupport.** `HttpModelClientSupport.sendBounded(http, request, providerLabel, maxResponseBytes)` consolidates the duplicated bounded-read + OOM-guard pattern; Claude / OpenAI / Ollama `sendChat` all delegate. `ModelClient.chatStream(messages)` (default impl) delegates to `chatStream(messages, jsonSchema = null)` instead of carrying a byte-identical 28-line clone.
- **#2800 — Dedup MCP client list/text-block + Skills factories.** 4 file-private helpers in `McpClient.kt`: `resultArray(result, key)`, `joinTextContent(blocks, contentKey)`, `prefixed(prefix, name)`, `makeMcpSkill(name, description, impl)`. `toolSkills`/`promptSkills`/`resourceSkills` all flow through the factory (8 boilerplate lines each → 3-4).
- **#2794 — `toLlmInput` + `jsonSerialize` collapse.** Both flow through a single parameterised `serializeForLlm(value, quoteTopLevelStrings)` walker. Deferred (out of scope for the maintainability pass — flagged in commit body): the `forEachGenerableParam` 6-walker unification and `constructFromMapReflective` 5-job split.
- **#2801 — Primary `(String) -> Any?` overload.** New `LiveShow.from(invoke, ...)` and `LiveRunner.serve(invoke, args, ...)` overloads. Future operator types just pass `myAgent::invokeSuspend` — no edit to LiveShow/LiveRunner required. The six typed overloads stay for source-compat.
- **#2807 — Detekt static analysis.** detekt 1.23.7 plugin wired into root `build.gradle.kts`. `detekt.yml` enables complexity (LongMethod, LargeClass, CyclomaticComplexMethod, NestedBlockDepth), exceptions (SwallowedException, TooGenericExceptionCaught/Thrown), style (MagicNumber with sensible allowlist, UnusedPrivateMember), naming (FunctionNaming), potential-bugs, empty-blocks. `detekt-baseline.xml` freezes current violations so the build stays green on existing code; new violations fail. README's "First 10 Minutes" lists `./gradlew detekt` alongside `./gradlew test`.

### Notes

- No API removals; every 0.6.5 caller compiles and runs unchanged.
- `agents_engine.model.JsonEscape` → `agents_engine.internal.JsonEscape`: only the `internal` qualifier is package-visible, so this is a binary-compatible relocation for any consumer using only the public API.
- The detekt baseline file (788 lines) is checked in; future PRs are held to the rules without retroactively forcing cleanup of the audited code.

## [0.6.5] — 2026-05-30

### Fixed — Hardcoded 60s LLM request timeout killed long Sonnet turns (#2850)

- **`ClaudeClient` / `OpenAiClient` / `DeepSeekClient` / `OllamaClient`** — bumped `DEFAULT_REQUEST_TIMEOUT` from `60.seconds` to `300.seconds`. Field report against 0.6.4 showed long Sonnet turns (multi-step agentic loops with extended thinking) consistently breached the 60s cap on the JDK HttpClient, surfacing as `HttpTimeoutException: request timed out` and tearing down the streaming `Flow`. New floor matches what production agents actually need; 0.6.5 callers see no behavior change unless they were silently relying on the truncation. `DEFAULT_CONNECT_TIMEOUT` stays at `10.seconds` — healthy networks never spend that long on TCP connect.
- **`model { requestTimeout = …; connectTimeout = … }`** — tunable from the DSL on every built-in provider (Ollama, Claude, OpenAI, DeepSeek). Both fields default to `null`, which falls back to the adapter's `DEFAULT_REQUEST_TIMEOUT` / `DEFAULT_CONNECT_TIMEOUT`. Set the override when long-context calls, big Ollama generations, or extended-thinking turns regularly approach 5 minutes. Wired through `ModelConfig.requestTimeout` / `connectTimeout` → `defaultClientFor()` → each adapter ctor — no shared global; per-agent, per-config, per-test.
- **No public API removals** — additive only. Existing `ModelBuilder` callers compile and run unchanged.

### Added — Files convenience surface

- **`agents_engine.content.Files`** — one-line file loading for the typed `Content` hierarchy. `Files.load(path, store): Content` reads the file, detects modality + mime from filename extension (case-insensitive, no magic-byte sniffing), puts bytes via the `BlobStore`, returns the right `Content` variant. Same `ContentRef.hash` as a manual `store.put`. Throws `UnknownExtensionException` (names the extension + path + full list of known extensions) on unrecognised.
- **Variants:** `loadOrNull` (null-on-unknown), `loadAll` (throws on first unknown), `loadAllOrSkip` (silently skips — directory ingestion), `canonicalExtensionFor(content)` (inverse mapping), `knownExtensions: Set<String>` (predicate for callers).
- Extension coverage: every `wireMime` on every modality variant has at least one canonical extension. Image: `png`, `jpg`/`jpeg`, `gif`, `webp`. Audio: `mp3`, `wav`, `flac`, `ogg`. Video: `mp4`, `webm`, `mov`. Document: `pdf`, `docx`, `md`/`markdown`, `html`/`htm`, `txt`.
- 13 unit tests pin per-extension mapping, hash round-trip, case-insensitivity, unknown-extension behavior on every entry point, and the canonical-extension inverse for all 17 variants.

### Added — Typed agent attachments (#2470 slice b)

- **`agent.invokeWithAttachments(input, attachments)`** + suspending sibling `invokeSuspendWithAttachments` — user-facing API for vision input via typed `Content.Image`. The runtime dereferences each ref against the agent's injected `BlobStore`, base64-encodes once, and attaches `ImagePart` to the first user `LlmMessage`. Per-provider wire translation is the slice-a work — this commit routes the typed surface into it.
- **`Agent.blobStore: BlobStore?` + `blobStore(store)` DSL** — optional injection; null when the agent doesn't take attachments. Passing attachments to an agent with no `blobStore` errors fast at invoke time with a clear message — caller misconfiguration surfaces before any provider HTTP.
- **Closed mime mapping** — `ImageMime → ImagePart.WireMime` for all four variants (`Png`, `Jpeg`, `Gif`, `Webp`). No `String` conversion at any boundary.
- **Forensic-friendly errors** — when a ref's blob is missing from the store, the error names the ref's hash prefix. Helps debug snapshot resumes against partially-purged stores.
- **Non-image variants skipped in v1** — `Content.Text` / `Document` / `Audio` / `Video` flow through the attachment path as no-ops. Slice c will wire Document via provider doc-input adapters; Audio/Video land in Stage 2.
- **Empty / all-skipped attachments → null images** — no provider sees an empty array; legacy wire shape preserved.
- **Resume composition** — `attachments` argument is ignored on resume because the restored conversation already carries the original `LlmMessage.images` on the saved user turn.
- **Tests:** 8 unit cases (`AgentAttachmentsTest`) + 6 live cases (`AgentVisionLiveTest`) running the same `VisionFixtures` from slice a through the agent surface on Ollama qwen3-vl:8b, Claude Haiku 4.5, OpenAI gpt-4o-mini. See [docs/multimodal.md](docs/multimodal.md#agent-attachments--typed-contentimage-at-the-invoke-surface-2470-slice-b).

### Added — Vision input across all providers (#2470 slice a)

- **`LlmMessage.images: List<ImagePart>? = null`** — new optional field; back-compat default leaves the wire shape byte-identical to pre-#2470 for callers that don't pass images. Closed `ImagePart(base64, wireMime)` with `WireMime` sealed type (`Png`, `Jpeg`, `Gif`, `Webp`) — `String` mime is intentionally not accepted in the public ctor.
- **Per-provider adapters** translate vision on `role = "user"` messages:
  - Ollama: `{role:"user", content:"text", images:["<b64>", ...]}` — works with `qwen3-vl:8b`, `llava`, `llama3.2-vision`, etc. Non-vision models silently ignore the field.
  - Claude: typed content array — `[{type:"text"}, {type:"image", source:{type:"base64", media_type:"image/png", data:"<b64>"}}, ...]`. Works with all Claude vision-capable models (Haiku 4.5, Sonnet 4.6, Opus 4.7).
  - OpenAI: typed content array — `[{type:"text"}, {type:"image_url", image_url:{url:"data:image/png;base64,<b64>"}}, ...]`. Works with gpt-4o, gpt-4o-mini, gpt-4-turbo, the o* reasoning models.
  - DeepSeek: inherits the OpenAI adapter shape; current DeepSeek models lack vision and silently ignore the field. Shape-tested; no live call to avoid spending on a no-op.
- **Role-gated:** non-user messages (system/assistant/tool) with non-null `images` ignore the field on the wire — no provider's API accepts images on those roles. Pinned by tests.
- **Programmatic fixtures** in `src/test`: `VisionFixtures.threeSquaresPng()` (256×256 red/blue/green squares for "count the squares" eval) and `VisionFixtures.housePng()` (256×256 cartoon house for "what is this?" eval). Rendered via `BufferedImage` + `ImageIO` — reproducible byte-for-byte across machines and CI, no external assets in the repo.
- **Live integration tests** (`VisionLiveTest`) cover all three vision-capable providers with cost discipline (`temperature = 0`, `maxTokens = 80`, single-turn, ~5KB base64 payloads): Ollama `qwen3-vl:8b` (tagged `live-llm`, runs via `:integrationTest`), Claude `claude-haiku-4-5` and OpenAI `gpt-4o-mini` (tagged `live-cloud-api`, runs in default `:test` with `assumeTrue` skipping when no key). Model names overridable via env. Assertion shape is loose keyword-match — robust against per-model phrasing variance.
- 8 wire-format unit tests pin per-provider JSON shape + the no-images back-compat path. See [docs/multimodal.md](docs/multimodal.md#vision-input--talking-to-the-model-2470-slice-a).

### Added — Multimodal foundation (#2465 epic, Stage 1)

- **Typed `Content` hierarchy (#2466)** — `sealed interface Content` with variants `Text`, `Image`, `Audio`, `Video`, `Document` in package `agents_engine.content`. Each non-text variant carries a `ContentRef` plus a typed mime (`ImageMime`, `AudioMime`, `VideoMime`, `DocMime`). Mime types are closed sealed interfaces with `wireMime: String` accessors — no `String` mime in any public API. Extension property `Content.modality: String` is the audit-stable per-variant name. Stage 1 wires Image + Document end-to-end (the modalities the 0.8 spec → product loop consumes); Audio + Video are modelled now and exercised through provider adapters in Stage 2 (#2470, deferred).
- **`ContentRef` + `BlobStore` (#2467)** — content-addressed reference (`hash: String` SHA-256 hex, `sizeBytes: Long`, `wireMime: String`). `BlobStore` interface with `InMemoryBlobStore` (defensive byte-array copies on put + get) and `FileBlobStore(dir)` (one file per blob, filename = hash, atomic tmp + rename, survives process restart, idempotent put). Hash family matches the manifest hash (#1912) and snapshot filename hash (#2753) — single algorithm across the audit surface. Public top-level `computeContentHash(bytes): String` for byte-level comparison without a store.
- **`ToolResult` (#2469)** — `data class ToolResult(parts: List<Content>)` for tools that return mixed content (a screenshot tool returns text + image; OCR returns extracted text + the source PDF ref). Just another `Any?` the tool executor returns — no `ToolDef` signature change; existing tools that return strings keep working byte-for-byte. AgenticLoop renders multipart returns as text + `[modality: <wireMime>] (<hash-prefix>, <size>B)` placeholders for the LLM tool-result message; provider-specific multipart rendering (vision-capable Claude/OpenAI/Gemini) is sibling #2470 (deferred). JSONL audit exporter gains an `outputParts: List<String>?` column on audit rows — for `ToolResult` returns it emits one entry per part as `<modality>:<hash-prefix>:<sizeBytes>:<wireMime>` (text parts as `text:inline:<charCount>:text/plain`); blob bytes never enter the audit row. Field is null for non-multimodal returns — legacy audit rows unchanged. `EXPECTED_FIELDS` schema-pin updated to include the new column. Composes with snapshot/resume (refs serialise, blobs stay external) and `untrustedOutput` (the text-summary rendering goes through the existing JSON envelope). See [docs/multimodal.md](docs/multimodal.md).

### Added — Eval harness (#2491 epic, feature-complete)

- **`DeterministicModelClient` (#2492)** — `agents_engine.testing.DeterministicModelClient(scripted: List<LlmResponse>)` (or vararg ctor) hands back pre-scripted responses one per `chat` call. No network, byte-deterministic. `requests` records every message list the agent built up; `remaining()` reports unconsumed responses. Exhaustion throws `DeterministicScriptExhausted(callIndex, scriptSize, lastMessages)`. Streaming uses the default `ModelClient.chatStream` wrap. Out of scope for v1: record-from-live HTTP capture (mentioned in the ticket — needs an HTTP-fixture story we'll write when there's demand) and per-token chunk replay.
- **`eval { }` DSL (#2493)** — `agents_engine.testing.eval<IN, OUT>("name") { input(...); expect { ... } }` builds a typed eval case. Three expectation styles: `expect("label") { predicate }` (typed predicate over `OUT`), `expectSnapshot(snapshot = "...")` (pin canonical `toLlmInput(output)` JSON; diff on regression), `expectFieldEquals(field, value)` (single-field substring on rendered JSON). Multiple expects compose — all must pass. `EvalResult.failureMessage` is null on pass, structured on fail with per-expectation diagnostics. `evalSuite("name") { + case; + case }.runAll(agent)` bundles cases; type-homogeneous over the agent type at call time (mixed-shape suite is a compile error). Composes with `DeterministicModelClient` for fully reproducible end-to-end agentic-loop eval against typed `OUT`. See [docs/eval.md](docs/eval.md).
- **LLM-as-judge scorer (#2494)** — `agents_engine.testing.JudgeRubric(criteria, scoreRange, judgeModel)` + `@Generable JudgeVerdict(score, rationale)`. Opt-in via `eval { ... judge("tone", rubric) }`. Verdicts surface on `EvalResult.judgeVerdicts: Map<String, JudgeOutcome>` keyed by label; sealed `JudgeOutcome { Scored(verdict) | Errored(detail) }` so parse failures or out-of-range scores surface without aborting. `EvalResult.passed` is structurally restricted to deterministic `outcomes` + `invocationError` — judges NEVER gate pass/fail. `EvalResult.judgeSummary` renders `[advisory] <label>: <score> — <rationale>` lines for test reports. The judge model is independent of the production agent's model: use `DeterministicModelClient` for unit tests (so the judge itself is reproducible) or a pinned cloud model for live eval. Judges don't run when the agent itself fails (no output to score). See [docs/eval.md](docs/eval.md).

## [0.6.4] — 2026-05-30

**"Trust patch."** Outside auditor reviewed 0.6.3 at 7.5/10 with the verdict *"useful hardening release, but not a repositioning release."* 0.6.4 is the deliberate response: boring on features, focused on closing every real boundary gap the audit found. The tagline:

> 0.6.4 makes Agents.KT more tolerant of real model behavior without weakening runtime boundaries.

Product identity is unchanged: auditable Kotlin agent runtime for regulated JVM teams. The #2752 epic batches six runtime/audit fixes + the docs-and-release-hygiene reconciliation; it also ships the #2655 prompt-caching epic completion that landed between 0.6.3 and 0.6.4. See [`RELEASE_NOTES.md`](RELEASE_NOTES.md) for the long-form release narrative.

### Added — Trust patch (#2752 epic)

- **`PipelineEvent.ToolHallucinated` — first-class audit event for unknown / unlisted tool calls (#2757)** — since #2476, hallucinated tool calls are recoverable and emit `ToolCallFinished(isError = true)`, but auditors could only distinguish "model hallucinated a tool" from "tool ran and returned an error" by parsing the error message body. Now a typed event with `requestedName`, `arguments`, `allowedTools` (skill-bounded, not the wider `agent.toolMap`), and `runtimeContext` (requestId / sessionId / manifestHash). New `Agent.onToolHallucinated { name, args, allowed -> }` listener mirrors `onToolDenied`; wired through `Agent.observe { }` so JSONL audit exporter and OTel / LangSmith / Langfuse bridges pick it up automatically. Streaming consumers still get `ToolCallFinished(isError = true)` on the same wall-clock — `ToolHallucinated` is additive evidence, not a replacement.
- **`MemoryBank.snapshotForAgent` / `restoreForAgent` — namespaced snapshot/restore (#2755)** — the shared-workspace topology now actually works. New per-agent accessors: `bank.snapshotForAgent(agentName): String?` and `bank.restoreForAgent(agentName, value: String?)`. AgenticLoop wires through them so resuming session A in a bank also holding session B's slot leaves B untouched.
- **`SnapshotManifestMismatchException` + `allowManifestMismatch` opt-in (#2754)** — `SessionSnapshot.manifestHash` was carried in 0.6.x for the restore guard but never enforced. Now fails closed by default: a snapshot taken under one tool/permission set refuses to replay against an agent whose manifest has since changed. Exception carries both `expected` and `actual` hashes for forensics. Callers who own the migration story pass `allowManifestMismatch = true`. `null` snapshot.manifestHash is allowed (back-compat with any pre-0.6.4 snapshots).
- **`onBudgetExceeded` broadened to TURNS / DURATION / TOKENS / CONSECUTIVE_TOOL (#2750)** — #2412 wired the handler for `TOOL_CALLS` only; the other reasons threw unconditionally even when a handler was registered. The handler contract is now symmetric across every cumulative throw site. `BudgetDecision.Extend(newLimit)` raises the cap and re-arms the `onBudgetThreshold` warning toward the new cap. Units: integer count for TOOL_CALLS / TURNS / TOKENS / CONSECUTIVE_TOOL; milliseconds for DURATION. PER_TOOL_TIMEOUT stays unconditionally throwing (extending a single in-flight tool needs interrupt semantics — separate ticket).
- **`BudgetDecision.Checkpoint` broadened to every cumulative cap (#2764)** — #2749 introduced Checkpoint at the TOOL_CALLS hook only; the four sites broadened by #2750 still ignored a Checkpoint return value and threw the plain `BudgetExceededException`, which discarded the in-flight state — the same history-replay tax #2749 was designed to remove. Now mirrors #2750's Extend coverage: a handler returning Checkpoint at TURNS / DURATION / TOKENS / CONSECUTIVE_TOOL captures a `SessionSnapshot` at the turn boundary, fires `onTurnCheckpoint`, and throws `BudgetCheckpointException`. Falls back to plain `BudgetExceededException` when `onTurnCheckpoint` is null (Stop semantics — same as the TOOL_CALLS site). The shared capture path now uses `MemoryBank.snapshotForAgent` per #2755 — incidentally fixes the pre-#2764 TOOL_CALLS Checkpoint, which still used the wipe-all `bank.entries()` and leaked other agents' slots into the snapshot.

### Changed — Trust patch (#2752 epic)

- **`FileSnapshotStore` hashes session ids before forming filenames (#2753)** — pre-#2753 the raw key flowed into `dir.resolve("$key.json")`, so a hostile session id like `"../../../etc/poisoned"` would let the caller read or write outside `dir`. The fix: SHA-256 hex hash for the filesystem name; the original session id stays inside the snapshot body (`sessionId` / `requestId`) for traceability. Deterministic — repeated saves with the same key overwrite atomically.
- **`wrapUntrustedToolResult` routes through the central `toJsonString` escaper (#2756)** — the old hand-rolled 5-char replace chain handled `\\ " \n \r \t` but left the rest of U+0000–U+001F unescaped, producing invalid JSON for binary / OCR / captured-terminal tool output. `String.toJsonString()` (added #2378) was the project-wide source of truth everywhere else; the local copy was the last holdout. Tool name is escaped through `toJsonString()` too — a name containing `"` no longer breaks the envelope. Tests cover NUL / BS / FF / ESC, emoji, and the pre-#2756 charset.
- **Docs + release-notes hygiene reconciled (#2752 workstream A)** — README dep coordinate 0.6.0 → 0.6.4 with the lead paragraph and "Current Release" blurb rewritten; `RELEASE_NOTES.md` fully refreshed from the stale v0.5.0 body; provider count consistent across `docs/model-and-tools.md` and `SECURITY.md` (four built-in adapters — Ollama / Anthropic / OpenAI / DeepSeek); unknown-tool documentation in `docs/prd.md` and `docs/model-and-tools.md` describes the recoverable-error path from #2476 (not the pre-0.6.3 `IllegalStateException`); MCP server adjunct (`src/main/resources/internals-agent/mcp/McpServer.md`) describes output via `toLlmInput` per #2483, not raw `toString()`; CHANGELOG duplicate `## [0.6.3]` header removed; [0.6.2] attribution claim annotated with the 0.6.3 revert.

### Added — Caching epic completion (#2655 — landed between 0.6.3 and 0.6.4)

- **Public snapshot/resume seam + `BudgetDecision.Checkpoint` (#2749)** — exposes the snapshot/resume primitives that have been carried inside `executeAgentic` as `internal` parameters since the 0.6.1 spike (#2416). Two new surfaces, both opt-in / additive / non-breaking:
  - **`Agent.invokeSuspendResuming(input, resumeFrom = null, onTurnCheckpoint = null): OUT`** — the public seam. Defaults match `invokeSuspend(input)` byte-for-byte. With `onTurnCheckpoint` set, the hook fires at every turn boundary with the in-flight `SessionSnapshot`. With `resumeFrom` set, the loop continues from the saved state (full conversation + counters + memory) without replaying history. Threads through the existing `invokeSuspendForSession` (gains the two parameters) → `executeAgentic`.
  - **`BudgetDecision.Checkpoint` + `BudgetCheckpointException(snapshot, reason, currentLimit)`** — third sealed variant alongside `Stop` / `Extend`. When an `onBudgetExceeded` handler returns `Checkpoint` AND an `onTurnCheckpoint` is registered on the invocation, the runtime captures the snapshot, delivers it to the hook, and throws `BudgetCheckpointException` (a subclass of `BudgetExceededException` — existing catch blocks still fire). Without an `onTurnCheckpoint`, falls back to `Stop` semantics. On resume, `toolCallLimit` is taken as `max(snapshot.toolCallLimit, agent.budget.maxToolCalls)` so a rebuilt agent with a raised cap honors the new ceiling.
  - **The UX this unblocks**: agent hits its tool-call cap mid-dialog → `Checkpoint` returns the snapshot → caller surfaces a "raise the cap and continue?" prompt → on acceptance, `agent.invokeSuspendResuming(input, resumeFrom = snapshot)` continues the same conversation. No history-replay tax. See [docs/wiki/extending-budgets-at-runtime.md](docs/wiki/extending-budgets-at-runtime.md) for the worked example.
- **Prompt caching across all providers (#2655 epic — #2657, #2658, #2659, #2661, #2662, #2663)** — completes the vendor-neutral caching epic started by the #2656 DSL in 0.6.3. The `caching { }` agent block now drives real cost/latency savings end-to-end:
  - **#2658 — Anthropic explicit `cache_control` breakpoints** in `ClaudeClient`. Emits `cache_control:{type:"ephemeral"}` on the system block (array form), the last tool definition (caches the tool-defs prefix), each `Custom` segment in `system[]`, and rolling-conversation breakpoints on the latest assistant/user message. TTL mapping: `Duration ≤ 5min` → default ephemeral; `> 5min` → `"ttl":"1h"`. Breakpoint budget coalesced at Anthropic's per-request cap of 4. Backward-compat: requests without cache hints emit the legacy `system: "<text>"` string form byte-identically.
  - **#2659 / #2661 — OpenAI / DeepSeek automatic prefix caching + `prompt_cache_key` routing**. OpenAI does automatic prefix caching above ~1024 tokens; the adapter now emits a `prompt_cache_key` derived from the agent identity (+ first 12 chars of `manifestHash` when present) so same-shape requests land on the same cache shard, improving hit rate. DeepSeek (OpenAI-compatible) inherits the same. Cached-input tokens already surface on `TokenUsage`.
  - **#2662 — Ollama / self-hosted engine APC**. Engines (Ollama context reuse, vLLM APC, SGLang RadixAttention) cache at the KV-cache level with no wire control; cache hints degrade to a documented no-op. Prefix stability — covered by #2657 — is what makes the engine cache hit. Pinned by `OllamaCacheHintNoopTest`: a hinted message produces a hint-free Ollama request body.
  - **#2657 — Prefix-stability guard**. The vendor cache silently misses on a non-byte-identical prefix; the framework now hashes each cache-hinted segment per-agent across invocations and emits a `WARNING` ("cacheable segment [SystemPrompt] for agent X changed between invocations") when it drifts. First-sighting pattern probe warns on Unix-millis timestamps, ISO-8601 datetimes, and UUIDs in cacheable content — the silent killers. State lives in a `WeakHashMap` keyed by `Agent` identity; off when the message has no cache hint.
  - **#2663 — Cache observability on `TokenUsage`**. New `cacheWriteTokens: Int? = null` field for Anthropic's premium-billed write side (~25% surcharge on first-write tokens; null on providers that don't expose it). Derived `cacheHitRate: Double?` returns `cachedInputTokens / promptTokens` when both are present. Cumulative `TokenUsage` in the agentic loop now sums `cacheWriteTokens` across turns alongside the existing `cachedInputTokens` accumulation.
  - **Gemini cached-content handles (#2660)** — deferred: no Gemini adapter exists in this codebase yet, so this slice is blocked on the underlying provider work.
  - See [docs/caching.md](docs/caching.md) for the per-provider behavior table, the `CacheHint` model, the prefix-stability rules, and the common cache-buster anti-patterns.

## [0.6.3] — 2026-05-29

**"Prompt-caching foundation + Koog-bug regression net."** Ships the vendor-neutral prompt-caching DSL — the foundation of the #2655 epic — and lands the first eight Koog issue-set regression checks under #2474 (five real fixes including the sealed `@Generable` parent-dispatch unblock, plus three regression-pin tests against the existing contracts).

### Added

- **Vendor-neutral prompt-caching DSL + neutral hint model (#2656, part of the #2655 epic)** — agent-controllable prompt caching declared in provider-agnostic terms. New `caching { }` block: `enabled` (default true), `cacheSystemPrompt` / `cacheToolDefs` (default true — byte-stable system prompt + KSP-stable tool defs, #1703), `cacheConversation = None | Rolling` (default `None`; opt-in because rolling has per-vendor write cost), `ttl` (null = provider default), plus a `cacheable(id, ttl) { content }` helper for per-segment marking of large retrieved documents / instruction sets. Internally, the agentic loop attaches a neutral `CacheHint(segment, ttl, breakpoint)` (with `sealed CacheSegment { SystemPrompt; ToolDefs; Conversation; Custom(id) }`) to `LlmMessage` at message-assembly time. `LlmMessage` gains an optional `cacheHint: CacheHint? = null` field — backward-compatible: existing adapters ignore it, preserving the pre-#2656 wire shape exactly. No provider cache types (`cache_control`, Gemini cache IDs, …) appear in the public API. Per-provider adapter consumption (Anthropic / OpenAI / Gemini / DeepSeek / Ollama) lands in #2658-#2662; stability guard in #2657; observability in #2663. See the [Prompt Caching](https://github.com/Deep-CodeAI/Agents.KT/wiki/Prompt-Caching) wiki page.
- **`SessionHistory` — ergonomic, stable history accessors over `AgentSession` events (#2485, addresses Koog signal under #2474)** — `class SessionHistory(events: List<AgentEvent<*>>)` exposes `toolCalls()` / `toolResults(excludeErrors = false)` / `assistantMessages()` / `completedOutput()` / `failed()` / `skillsStarted()`. Thin wrapper — no new state, deterministic ordering from the source flow, no allocation beyond filtered list materializations. `ToolCallRecord(callId, toolName, arguments)` and `ToolResultRecord(callId, toolName, result, isError)` are the surfaced shapes. **Not in v1**: a `userMessages()` accessor — the agent input is passed to `agent.session(input)` directly and is not surfaced as an event; adding it requires a new `AgentEvent.UserMessage` and is out of scope for this slice.

### Changed

- **Unknown / unlisted tool name mid-loop is now recoverable, not fatal (#2476, regression for Koog signal under #2474)** — when the model emits a tool name absent from the active skill's allowlist (whether outright unknown or belonging to a different skill on the same agent), the agentic loop previously threw `IllegalStateException` and the run died. It now appends a tool-result message naming the bad call and listing the skill's allowed tools, then continues — so the model gets a turn to self-correct. The disallowed executor still never runs (authorization boundary unchanged), the skill's allowlist is the only set named (no leak of the wider `agent.toolMap`), and streaming consumers see a `ToolCallFinished(isError = true)` for the rejected call. Pinned by `KoogRegressionUnknownToolTest`; `ToolAuthorizationTest` rewritten to assert the recovery contract (two of its prior assertions were accidentally passing via `fail()` message contents — replaced with honest tool-message inspection).
- **`McpServer` tools/call now serializes `@Generable` outputs as JSON, not as Kotlin debug `toString` (#2483, regression for Koog signal under #2474)** — `McpServer.handleToolCall` previously rendered the executor's return value through `output?.toString()`, leaking the Kotlin data-class debug shape (`SearchPayload(text=Hello, source=wiki)`) into the MCP text content. Routed through `toLlmInput` instead: `@Generable` outputs render as JSON (`{"text":"Hello","source":"wiki"}`), `String` stays clean, and primitives stay clean. Non-`@Generable` typed outputs still fall back to `.toString()` — documented limitation, register a `@Generable` output type for typed MCP boundaries.
- **Enum-typed fields now appear in JSON Schema with a typed value list (#2479 part 1, regression for Koog signal under #2474)** — `KType.jsonSchemaTypeObject` previously fell through to `{"type":"string"}` for enum-typed constructor parameters, so the LLM had no way to know which values were valid and the constrained-decoding provider path couldn't enforce them. Enums now render as `{"type":"string","enum":["veryHigh","normal","low"]}` with constant names emitted **verbatim** from `Enum.name` — no case mutation, no `@SerialName`-style lowercasing. Mixed-case constants (`RED` / `Green` / `blue`) survive intact. The tool_choice configurability half of #2479 is a separate slice (`ToolChoice { Auto | Required | None | Specific(name) }` API + adapter wiring).
- **Sealed `@Generable` parent classes now deserialize via type-discriminator dispatch (#2482a, regression for Koog signal under #2474)** — `KClass<Sealed>.constructFromMap(...)` previously returned null because `primaryConstructor` is null on sealed parents. The schema-gen path emits `{"oneOf": [...]}` for sealed types, so any MCP-exposed skill (or other typed entry point) declaring a sealed `@Generable` input was unusable — the model could produce a matching payload, the server couldn't read it. `constructFromMapReflective` now checks `isSealed`, looks up the matching variant by the `type` discriminator, and recurses — including the `data object` case via `objectInstance`. Unknown variants and missing-discriminator maps return null so the call routes through `onError.invalidArgs` instead of constructing a wrong-shape value.
- **Stringified-JSON coercion for nested object / list / sealed fields (#2482b, regression for Koog signal under #2474)** — when the LLM emits a typed field whose value is a JSON string (instead of a nested object / array), `coerceValue` now parses the string with `LenientJsonParser` and continues coercion. Guarded: `String` fields are NOT JSON-decoded (a value like `"The {weather} report"` stays the literal string — `String::class` matches first in the `when`), and unparseable JSON for an object/list field returns null so the failure routes through `onError.invalidArgs`. Composes with #2482a — a sealed-typed field accepts a JSON string carrying the type discriminator.

### Tests

- **Koog issue-set regression suite — first slice (#2474)** — pin Agents.KT contracts where Koog broke. #2475 ships `KoogRegressionWrongTypedArgsTest` (3 cases): (1) scalar `Number → String` is intentional coercion per `coerceValue` (not a malformed arg — executor runs with the stringified value); (2) a truly-unparseable value for a typed field (e.g. `"abc"` for `count: Int`) routes through `onError.invalidArgs` with end-to-end recovery via `RepairResult.Fixed`, executor runs exactly once for the repaired call; (3) without a handler the failure is the framework's `ToolExecutionException` with typed-arg context — never a raw `kotlinx.serialization` / `NumberFormatException`.
- **Koog regression — loop protection (#2480)** — `KoogRegressionLoopProtectionTest` (4 cases) pins `budget { maxConsecutiveSameTool = N }`: same tool past the cap throws `BudgetExceededException(reason = CONSECUTIVE_TOOL)` naming the offending tool; an interleaved call resets the counter (`alpha → beta → alpha → beta`) so an alternating agent doesn't trip; *name-only* semantics — varying args still trip the cap (stricter than the Koog signal's "identical args" framing — Agents.KT catches more loop shapes); pre-cap threshold listener (`onBudgetThreshold`) fires for `CONSECUTIVE_TOOL`. Repeated-identical-assistant-output detection mentioned in the Koog signal is NOT yet implemented — known gap, separate detector if/when needed.
- **Koog regression — OpenRouter-style streaming chunk reconstruction (#2478)** — `KoogRegressionStreamingChunkReconstructionTest` (3 cases) feeds synthetic chunk sequences through `chatOrStream` and pins: OpenRouter shape (toolName in the first `ToolCallStarted` only, args split across N `ToolCallArgumentsDelta` chunks, finalized by `ToolCallFinished`) reconstructs into one coherent `LlmResponse.ToolCalls` entry with full args; every wire arg-delta surfaces as exactly one `AgentEvent.ToolCallArgumentsDelta` event in arrival order verbatim (so streaming UIs can show JSON building up); interleaved chunks for parallel calls route by `callId` and reconstruct both calls cleanly; an orphan args delta (no preceding `ToolCallStarted`) doesn't crash the aggregator and doesn't fabricate a `Started` — the delta still fires as a consumer event so a UI sees the wire activity.
- **`ClaudeClientChatStreamLiveTest` stabilised (#2723)** — `1..50` prompt was still small enough for Haiku 4.5 to occasionally batch the entire response into ~3 same-millisecond SSE chunks, failing the `>=10ms gap OR >=5 chunks` assertion intended to catch wire-level re-bundling regressions. Bumped to `1..200`; three consecutive validation runs each report 8 chunks across ~1.3s of streaming. Also corrected the test's stale doc comment — the actual `@Tag` is `live-cloud-api` running under default `:test`, not `live-llm` running under `:integrationTest`.

## [0.6.2] — 2026-05-29

**"Attribution you can filter by."** Closes the bridge-observability gap that every downstream Langfuse / LangSmith / OTel consumer was working around: business identifiers flow through the runtime context, so bridges drop their per-bridge `ConcurrentHashMap<requestId, userId>` + `onBeforeTurn` capture pattern and read user / project / dialog identifiers directly off `AgentRuntimeContext`. Bundles the entire 0.6.1 release because 0.6.1 shipped on a parallel branch and never reached main — see the [0.6.1] section for the carried-forward bullets.

> **Reverted in 0.6.3.** The `AgentRuntimeContext.attribution` surface added below was reverted in 0.6.3. Today's `AgentRuntimeContext` carries only `requestId`, `sessionId`, and `manifestHash`. The current position: attribution is a deployer concern, not a framework concern — the integrating bridge / API gateway / session boundary owns its own side-channel and can attach arbitrary identifiers in its own layer without the framework opining on the schema. The bullet below is retained as the historical record of what shipped under the 0.6.2 tag, but consumers should not depend on the attribution API.

### Added

- **Business-attribution on `AgentRuntimeContext` (#2720)** [*reverted in 0.6.3, see note above*] — `AgentRuntimeContext.attribution: Map<String, String>` plus typed `userId` / `projectId` / `dialogId` accessors. Set once at the session boundary via `withAgentRuntimeContext(...)`; every nested `AgentEvent` / `PipelineEvent` surfaces it, and bridges (Langfuse / LangSmith / OTel) read it directly instead of capturing it themselves. Free-form keys are honoured for product-specific identifiers; the typed accessors are conveniences over well-known keys.

### Bundled from 0.6.1

Because 0.6.1 shipped on a parallel branch and was never merged to main, its content is also published in the 0.6.2 artifacts. Full bullets in the [0.6.1] section below:

- Snapshot/resume foundation (#2416, experimental)
- Reasoning/thinking stream across providers (#2406)
- `onBudgetExceeded` — raise a budget cap and continue (#2412)
- `onToolDenied` + `PipelineEvent.ToolDenied` (#2395)
- Typed parameter schemas for built-in tools (#2379)
- AI Act-aligned whitepaper draft (#1921, engineering guidance not legal advice)

### Dependencies

- `org.jline:jline` 3.27.1 → 4.1.2 — major version bump. `LiveShow` / `LiveRunner` REPL exercises `LineReaderBuilder`, `TerminalBuilder`, `DefaultHistory`, `EndOfFileException`, `UserInterruptException` — all source-compatible across the 3→4 boundary; no callsite changes in `agents_engine.runtime.LineEditor`.
- `com.google.devtools.ksp:symbol-processing-api` 2.3.7 → 2.3.8 (KSP module).
- Gradle wrapper 9.5.0 → 9.5.1.

Verified: full `./gradlew test` green on the new toolchain — 1596 tests, 0 failures across all 7 modules.

## [0.6.1] — 2026-05-28

> **Note:** 0.6.1 was cut on a parallel branch and never merged to main. Its content is re-published in the 0.6.2 artifacts (see [0.6.2] above). The dated bullets here document what shipped under the 0.6.1 tag for the audit trail.

### Added

- **Kimi (Moonshot AI) provider (#2697)** — `model { kimi("moonshot-v1-8k") }` (or `-32k` / `-128k` for the long-context variants) joins Ollama, Anthropic, OpenAI, and DeepSeek as a built-in `ModelClient`. Kimi's wire format is OpenAI-compatible, so `KimiClient` is a thin `OpenAiClient` subclass with provider identity `("kimi" / "Kimi")` and base URL `https://api.moonshot.cn`. `supportsConstrainedDecoding = false` (Kimi's `response_format` does not currently accept OpenAI's `json_schema` payload). Key loading from `.secrets/kimi-key` (file-backed, primary) with `KIMI_API_KEY` env override, mirroring the sibling adapters. `ModelProvider.KIMI` is wired through `defaultClientFor`, `semconvProviderName`, the permission-manifest provider mapping, and `ModelConfig.toString` masking. Unit-test parity with `DeepSeekClientTest` (wire format, token-usage identity, error envelope, headers, DSL). Live integration test (`KimiClientIntegrationTest`) gated `live-llm` for now — the local Moonshot key returns `Invalid Authentication` (verified via direct `curl`); flip the tag to `live-cloud-api` once the key validates so it gains default-suite coverage like DeepSeek.
- **OpenRouter provider (#2701)** — `model { openrouter("anthropic/claude-3.5-sonnet"); apiKey = ... }` joins Ollama / Anthropic / OpenAI / DeepSeek / Kimi under the same `ModelClient` interface. OpenRouter is an OpenAI-compatible aggregator that fronts hundreds of upstream models (`provider/model:variant` ids — including `:free`-tier variants). `OpenRouterClient` is a thin `OpenAiClient` subclass with provider identity `("openrouter" / "OpenRouter")`, base URL `https://openrouter.ai/api`, and two optional attribution headers OpenRouter recognizes — `openRouterHttpReferer` (origin URL) and `openRouterXTitle` (app name) — surfaced via the same `ModelConfig` DSL. Header merge logic lives in `OpenRouterClient.withOpenRouterHeaders`; injected for both `chat` (`sendChat`) and `chatStream` (`sendChatStream`). `supportsConstrainedDecoding = false` because upstream behavior varies widely; framework-level constrained decoding stays off so `@Generable` output parsing remains prompt/parser-driven regardless of which upstream the request routes to. Key loading from `.secrets/open-router-key` (file-backed, primary) with `OPENROUTER_API_KEY` env override. `ModelProvider.OPENROUTER` wired through `defaultClientFor`, `semconvProviderName`, and the permission-manifest provider mapping. Unit tests pin wire-format parity with the OpenAI sibling adapters plus header injection (configured / absent / both chat & stream paths). Live integration tests against free-tier models (`meta-llama/llama-3.2-3b-instruct:free`, `openai/gpt-oss-20b:free`) gated `live-llm` — free upstreams hit rate limits and rotate availability unpredictably, so they live outside the default `:test` to keep CI green; run via `:integrationTest`.
- **Snapshot/resume foundation (#2416, spike for #2386) — experimental** — an agent's resumable state is its message history + loop counters, so resume re-enters the loop seeded with a snapshot rather than suspending a coroutine. Ships `Snapshotable<S>`, `SessionSnapshot`, `SnapshotStore` (+ `InMemorySnapshotStore` and `FileSnapshotStore` with atomic temp-write/rename), `MemoryBank` snapshot/restore, and the `executeAgentic` turn-boundary checkpoint + `resumeFrom` seam. Round-trip proven by test (3 turns → crash → fresh agent → restore → finish). The ergonomic `persistence { }` DSL + `Agent.resumeOrStart(sessionId)`, the manifest-hash restore guard, and composition snapshots are the next phases on #2386.
- **Reasoning/thinking surface across providers (#2406)** — opt-in `model { reasoning(budgetTokens = …, effort = …) }` streams a model's reasoning separately from its answer as `AgentEvent.Reasoning` (with `LlmChunk.ReasoningDelta` and accumulated `LlmResponse.reasoning`). Off by default — no behavior change or added cost until enabled. Claude (extended thinking — forces temperature 1), DeepSeek (`reasoning_content`), and Ollama (`think:true` → `message.thinking`) emit reasoning text; OpenAI Chat Completions surfaces `reasoning_effort` + `TokenUsage.reasoningTokens` only (no reasoning text on the wire — Responses-API summaries are out of scope). Tracing bridges record reasoning *length* only (PII-safe); the JSONL audit exporter omits it. See [docs/streaming.md](docs/streaming.md) and [docs/model-and-tools.md](docs/model-and-tools.md).
- **`onBudgetExceeded` — raise a budget cap and continue (#2412)** — when a budget cap would throw `BudgetExceededException`, `onBudgetExceeded { reason, currentLimit -> }` is consulted: returning `BudgetDecision.Extend(newLimit)` raises the cap and continues, `BudgetDecision.Stop` (or no handler / a non-greater limit) throws as before. Currently wired for the tool-call cap, so a long-running agent can grant itself more tool calls mid-run ("hit 32 but need to continue") instead of failing. Off by default — no behavior change unless registered.
- **`onToolDenied` hook + `PipelineEvent.ToolDenied` (#2395)** — tool calls blocked by an `onBeforeToolCall` `Decision.Deny` are now first-class observable. Previously a denied call never fired `onToolUse` (its executor never ran), so audit/observability built on `onToolUse` or `observe{}` silently dropped every blocked attempt. `onToolDenied { name, args, reason -> }` now fires in its place (under the runtime context, so `requestId`/`sessionId`/`manifestHash` correlate), and `observe{}` surfaces it as `PipelineEvent.ToolDenied`. `onToolUse` still does not fire on denial.

### Changed

- **Built-in tools now declare typed parameter schemas (#2379)** — `memory_write` / `memory_search` carry `@Generable` arg types, `memory_read` an explicit closed no-args schema, `forum_return` a closed `value`-only schema, and swarm `absorb` delegates a typed `{query: String}` schema. Previously these relied on the providers' permissive empty-properties fallback (`additionalProperties: true`), forcing the model to infer argument shapes from the description prose. No public API change.

### Docs

- **AI Act-aligned whitepaper draft (#1921, engineering guidance not legal advice)** — markdown source of the regulated-deployment whitepaper draft: capability inventory, action log, decision points, failure modes, data lineage, vendor risk; EU AI Act articles (Art. 9 / 12 / 13 / 14 / 15) mapped to specific Agents.KT artefacts; evidence-pack template. Published as `docs/whitepapers/regulated-deployment.md`.

## [0.6.0] — 2026-05-23

**"Boundaries you can audit."** The 0.6.0 epic (#1911) turns Agents.KT's typed-boundary model into auditor-ready evidence: deterministic permission manifests with runtime hash correlation, append-only JSONL audit, before-interceptor guardrails, typed tool / MCP-tool hierarchies, vendor-neutral observability bridges (OTel / LangSmith / Langfuse), constrained decoding for `@Generable` outputs, DeepSeek as a fourth provider, and onTokenUsage telemetry. Existing consumers see no behavior change unless they opt into the new surfaces.

### Added

#### Permission manifest — the 0.6.0 hero feature (#1912)

- **`:agents-kt-manifest` module** — `agentManifest(agent)` returns a deterministic capability graph: every agent, skill, tool, knowledge entry, MCP endpoint, provider, budget, and policy boundary in a system, in YAML or JSON, with stable ordering and masked provider secrets.
- **`verifyAgentManifest` Gradle task** — diffs the current manifest against a checked-in baseline; fails the build on capability widening (new tools, new MCP endpoints, broader policies) so reviewers always see surface-area changes before they merge.
- **Manifest SHA-256 propagates into the runtime** — every `PipelineEvent` / `AgentEvent` carries the `manifestHash` of the agent that emitted it, so static manifest and dynamic audit trace tie back to the same approved capability set.
- **Provider secrets masked** — API keys, base URLs containing credentials, and any field marked `@SecretSafe` are redacted from the emitted manifest.

#### Runtime event context (#1913)

- **`manifestHash`, `requestId`, `sessionId` on every runtime event** — `PipelineEvent` and `AgentEvent` both carry them, so JSONL audit / OTel / LangSmith / Langfuse downstreams all bind events to the manifest hash that was authoritative at invocation time.
- **`withAgentRuntimeContext { ... }` extension** — Kotlin-coroutines-context-aware threading so nested compositions (`then`, `branch`, `loop`, `forum`, `wrap`) inherit the outer request/session/manifest correlation without re-derivation.

#### JSONL audit exporter (#1914)

- **`:agents-kt-observability` `JsonlAuditExporter`** — append-only, one-line-per-event audit format with `requestId`, `sessionId`, `manifestHash`, agent/skill/tool ids, event type, provider, and model. Raw arguments and results are omitted by default; opt-in via `includeRawArgs = true` / `includeRawResults = true` when the audit consumer needs them.
- **Stable canonical field ordering** — same audit row produces the same JSON line on every run, so the file is grep-friendly and diff-able.
- **PII-safe defaults** — designed for the regulated-deployment workflow in `docs/regulated-deployment.md`.

#### Before-interceptor guardrails (#1907)

- **`onBeforeSkill` / `onBeforeToolCall` / `onBeforeTurn`** — Rails-style interceptors returning a sealed `Decision { Proceed | ProceedWith(...) | Deny(reason) | Substitute(result) }`. Sibling to the post-hoc `onToolUse` / `onSkillChosen` / `onError` observer hooks already in 0.4.x.
- **Chain semantics** — interceptors run in registration order; every interceptor runs; the first non-`Proceed` wins; `Deny` short-circuits with an `onUnauthorizedToolCall`-shaped audit event; `Substitute` skips the model and returns the substituted value.
- **Unified use cases** — per-client tool policy (McpServer per-principal allowlists), action confirmation (`Escalate(reason, reviewerRole)` resumed by the host app), prompt-injection filtering as a one-liner, uniform `perToolTimeout` wrapping. See `docs/interceptors.md`.

#### Declarative tool policy (#1915)

- **`ToolPolicy` DSL** on `tool { policy { … } }` — declares tool risk (`LOW` / `MEDIUM` / `HIGH` / `CRITICAL`) plus filesystem / network / environment declarations. Consumed by the permission manifest and by audit-row formatters.
- **No runtime enforcement yet** — the sandbox-enforcement work is deferred to 0.7.0 (#1916). 0.6.0 ships the *declaration* surface so manifest reviewers can already see "this tool reads `~/.ssh`" or "this tool calls `*.openai.com`" at policy-review time.

#### Typed tool + MCP-tool hierarchies (#1948)

- **`Tool<IN, OUT>` typed handles** — `tool<Args, Result>("name", "desc") { args -> ... }` returns a `Tool<Args, Result>` with phantom types so `Skill.tools(addTool, divideTool, …)` is compile-time-checked instead of stringly-typed.
- **`McpTool<IN, OUT>`** — every MCP-imported tool also gets a typed handle via `McpClient.tools(prefix)`. Composes with the same `Skill.tools(...)` builder. Additive alongside the existing `MCP-as-skill` adapter.

#### MCP server hardening (#1902)

- **Inbound bearer auth** — `McpServer.tokens(...)` configures principal → token mappings; unauthenticated requests get a structured 401. `McpStdioServer` shares the same authn surface for stdio deployments.
- **Host / Origin allowlists** — DNS-rebinding and CSRF defenses against browser-side `localhost` exploits; explicit allowlist required for non-loopback hosts.
- **Per-principal tool policy** — each principal can have its own subset of agent skills exposed as MCP tools. Policy decisions flow through the `onBefore*` chain and into audit events.
- **Default-deny** — unconfigured server rejects everything except `initialize` / `tools/list`; opt-in for each authorization grant.

#### Stdio MCP server transport (#2045)

- **`McpStdioServer.from(agent)`** — exposes the same agent surface (tools, prompts, resources, `tools/listChanged: false`) over line-delimited stdio instead of HTTP. Same authentication + policy plumbing as the HTTP server.
- **`McpRunner --stdio`** — picocli-style one-liner for shipping agents as stdio-MCP services without a Gradle dependency on `:server`-style infrastructure.

#### LiveShow line editing (#985)

- **`LineEditor`** — line-discipline-aware input handling for the LiveShow runner: cursor movement, history, kill-line, basic readline-style navigation, all while the agent streams events to the display.
- **Cancellation-safe** — collector cancellation propagates through the editor; no orphaned threads.

#### Runtime observability bridge (#1908)

- **`ObservabilityBridge` in `:agents-kt-observability`** — vendor-neutral bridge contract with `onPipelineEvent`, `onAgentEvent`, and `onInterceptorDecision`, plus `.observe(bridge)` for one-call wiring.
- **`:agents-kt-otel` module** — OpenTelemetry adapter that maps agent sessions to `agent.invoke` spans, model turns to `gen_ai.chat` spans, tool calls to `gen_ai.tool` child spans, errors to span status, usage to GenAI attrs, and before-interceptor decisions to span events.
- **`:agents-kt-langsmith` module** — LangSmith run-tree adapter that maps skill invocations to `chain` runs, model turns to child `llm` runs, tool calls to child `tool` runs, failures to run errors, budget threshold events to run extras, and interceptor decisions to run tags. Dispatch is asynchronous, batched, oldest-drop under backpressure, and never throws into the agent path.
- **`:agents-kt-langfuse` module** — Langfuse trace adapter that maps skill invocations to traces, model turns to generations, tool calls to spans, runtime events to Langfuse events, and interceptor decisions to tags plus `interceptor.decision` observations. Dispatch is asynchronous, batched, oldest-drop under backpressure, and uses Langfuse's native ingestion endpoint without a vendor SDK.
- **Core remains vendor-free** — OTel, LangSmith, and Langfuse integration code is isolated to adapter modules.

#### Provider constrained decoding (#1949)

- **`@Generable` schemas are threaded into provider payloads** — OpenAI receives `response_format.json_schema`, Ollama receives `format`, and Anthropic receives a structured-output tool path for typed agentic outputs.
- **Provider capability detection** — `ModelClient.supportsConstrainedDecoding` gates schema forwarding so unsupported adapters keep the existing repair-loop behavior.

#### DeepSeek provider adapter

- **`model { deepseek(name); apiKey = ... }`** — OpenAI-compatible Chat Completions adapter with DeepSeek provider identity, configurable `deepSeekBaseUrl`, usage normalization, streaming through the OpenAI-compatible SSE path, and manifest provider metadata.
- **Constrained decoding stays disabled for DeepSeek** — the adapter does not send OpenAI `response_format.json_schema` because DeepSeek documents JSON-object mode rather than that schema payload.

#### Token usage telemetry (#2354, #2355, #2356, #2357)

- **Public `Agent.onTokenUsage { usage: TokenUsage -> }` listener** — fires once per successful LLM round-trip that reports usage, including streaming paths at end-of-stream. Tool-use cycles fire once per provider response, not once per agent invocation.
- **Widened `TokenUsage`** — now carries `promptTokens`, `completionTokens`, `cachedInputTokens`, `provider`, and `model`. `total` remains prompt + completion; cached tokens are a provider-visible subset of prompt tokens, not an extra addend.
- **Provider-normalized usage mapping** — Anthropic maps `input_tokens` / `output_tokens` / `cache_read_input_tokens` with `provider = "claude"`; OpenAI maps `prompt_tokens` / `completion_tokens` / `prompt_tokens_details.cached_tokens` with `provider = "openai"`; Ollama maps `prompt_eval_count` / `eval_count` with `cachedInputTokens = null` and `provider = "ollama"`.
- **Listener safety semantics** — missing usage does not fire, LLM failures do not fire and remain covered by `onError`, multiple listeners run in registration order, and listener exceptions are logged and swallowed so telemetry cannot break the agent run.

### Tests

- Added `OnTokenUsageTest` coverage for widened fields, multi-listener ordering, listener-error swallowing, missing-usage skip, model-failure skip with `onError`, multi-turn tool-use ordering, and streaming single-fire behavior.
- Updated Anthropic, OpenAI, and Ollama adapter tests to assert provider/model/cache mapping for normal and streaming responses.

### Added

#### InternalsAgent — framework documents itself via MCP (#1837)

- **`buildInternalsAgent(): Agent<String, String>`** in `agents_engine.runtime.internals` — a self-hosting docs agent whose skills correspond 1:1 to source files in the framework (63 today). Each skill is `implementedBy { _ -> loadResource("internals-agent/<path>.md") }` — no `model { }` configured because the IDE's LLM does the reasoning.
- **`Main.kt`** runner exposes the agent via `McpServer.from(...)` over Streamable HTTP. Default port 8765; override via `--args="<port>"`.
- **`./gradlew runInternalsAgent`** Gradle task. See `docs/internals-agent.md` for Claude Desktop / Cursor MCP wiring.
- **Classpath-scan registration** — `buildInternalsAgent()` walks `src/main/resources/internals-agent/` at construction time, deriving skill names from paths (`internals-agent/core/Agent.md` → `core_agent_kt`) and pulling `description:` from YAML-style frontmatter. Adding a new source-file adjunct is a one-`.md`-file change — no `InternalsAgent.kt` edit.
- **`validateInternalsAdjuncts` Gradle task** wired into `check` — CI guardrail that fails the build if any adjunct lacks `description:` frontmatter.

#### Distribution

- **GitHub Packages as secondary publication target (#1927)** — `publishAllPublicationsToGitHubPackagesRepository` published alongside the existing Sonatype path. Maven Central remains the primary public channel; GitHub Packages is for CI snapshots, PR previews, Sonatype-outage redundancy, and authenticated early-access. See `PUBLISHING.md` GitHub Packages section for consumer-side wiring + when to use which channel.

#### Documentation

- **`docs/internals-agent.md`** — InternalsAgent quickstart + IDE wiring (Claude Desktop, Cursor) (#1837).
- **`docs/threat-model.md`** — five deployment scenarios (safe-local / internal-tool / MCP-gateway / multi-agent swarm / anti-patterns), trust boundaries, gap-vs-framework matrix (#1904).
- **`docs/production-hardening.md`** — actionable pre-launch checklist organized by tool surface / MCP / budgets / secrets / observability / governance / operational; pre-launch ritual (#1919).
- **`docs/regulated-deployment.md`** — capability inventory, action log, decision points, failure modes, data lineage, vendor risk; EU AI Act mapping (Art. 9 / 12 / 13 / 14 / 15 → Agents.KT artefact); evidence-pack template (#1919).
- **`docs/comparison.md`** — side-by-side against LangChain / Semantic Kernel / AutoGen / raw MCP. Honest about losses; 8-shortcut "Choosing" subsection that sometimes points away from Agents.KT (#1906).
- **`docs/interceptors.md`** — `onBefore*` interceptor family + `Decision` sealed type reference (#1907).
- **`docs/observability.md`** — JSONL audit exporter reference plus the shipped `ObservabilityBridge` contract, `agents-kt-otel`, `agents-kt-langsmith`, and `agents-kt-langfuse` adapters (#1908, #1909, #1910, #1914).

### Changed

- **`InternalsAgent.kt` refactored from 63 hand-written skill blocks to a single classpath scanner** (#1837). 493 → 152 lines. Adding a source-file adjunct is now a one-`.md`-file change. Frontmatter is the single source of truth for the LLM-facing tool description.
- **README streaming-claims reconciliation** (#1901) — dropped the stale "no per-adapter native streaming yet" bullet that contradicted the next bullet's "all three adapters stream natively". Phase 2 roadmap entry updated to reflect v0.5.0-shipped per-adapter streaming.
- **README release positioning** (#1922) — hero, section order, and non-goals now lead with the 0.6.0 "auditable Kotlin agent runtime" story: manifest evidence, runtime audit correlation, least-privilege tools, and explicit deployer responsibilities.
- **PUBLISHING.md GPG setup** (#1905) — passphrase-protected key is now the recommended default. Empty-passphrase path preserved as a labelled fallback for isolated environments. "Why not `%no-protection`?" callout explains the threat model.
- **Live-test classification split** — `live-cloud-api` tag (DeepSeek / Anthropic / OpenAI direct against hosted APIs) runs in default `:test` so cloud-provider regressions are caught alongside unit tests; the broader `live-llm` tag (Ollama / Ollama Cloud) stays excluded from default `:test` due to upstream infra flakiness and runs via `:integrationTest`. `testAll` aggregator covers all five 0.6.0 subprojects plus both live slices.

### Fixed

- **Session-aware tool calls respect `perToolTimeout` (#1903)** — the `sessionExecutor` path now honors `budget.perToolTimeout`, emits a failed `ToolCallFinished` event on timeout, and surfaces `BudgetExceededException(PER_TOOL_TIMEOUT)`. Pre-fix, only the blocking-tool path enforced the per-tool timeout; session-aware suspend tools could hang indefinitely on a wedged backend.
- **Provider JSON string escaping (#2378)** — `OpenAiClient`, `OllamaClient`, and `ClaudeClient` each carried an identical hand-rolled escaper that only escaped `\ " \n \r \t`, producing invalid JSON whenever a tool result or prompt contained any other U+0000-U+001F codepoint (NUL bytes from binary tool output, U+000C form-feed from Tesseract OCR / PDF extraction, U+001B ESC from captured terminal output, etc.). Extracted the existing RFC 8259-conformant implementation from `InlineToolCallParser.kt` into `agents_engine.model.JsonEscape.kt` as a single internal `String.toJsonString()`; removed the three buggy private copies plus the duplicate inside `InlineToolCallParser`. Now escapes `\b` / `\f` / `\n` / `\r` / `\t` short forms and `\u00XX` for every remaining U+0000-U+001F; `\` and `"` unchanged; forward slash deliberately left literal.
- **MCP tool `inputSchema` forwarding (#2377)** — `McpClient.toolDefs()` now passes each MCP server's `inputSchema` through to the provider's wire `parameters` field via the new `ToolDef.parametersSchemaJson: String?` slot. Before, MCP-imported schemas only surfaced in the description prose while the wire `parameters` fell back to a permissive empty-object — conflicting signal. Provider resolution order: `argsType.jsonSchema() ?? parametersSchemaJson ?? <permissive empty>`.
- **Ollama transient-error retry (#2380)** — `OllamaClient.chat()` now retries transport-level failures wrapped in Ollama's `{"error":"..."}` envelope: `unexpected EOF`, `Internal Server Error`, `Service Unavailable`, `Bad Gateway`, `Gateway Timeout`, `connection reset`. Three attempts max with 250ms / 500ms backoff (~750ms worst-case latency added to a real outage). Non-transient errors — model-not-found, capability mismatch, auth, malformed-request — still fail fast on attempt 1. Capability-mismatch path still threads through the existing inline-tool fallback.

### Tests

- Added `ObservabilityBridgeTest`, `OtelBridgeTest`, `LangSmithBridgeTest`, and `LangfuseBridgeTest` coverage for bridge forwarding, observer stacking, session events, interceptor decisions, OTel parent context propagation, tool child spans, LangSmith run-tree shape, Langfuse trace/span/generation shape, async backpressure logging, usage attrs, and error status mapping.
- Added `DeepSeekClientTest` coverage for provider identity, OpenAI-compatible tool payloads, disabled schema forwarding, error envelopes, headers, and the `model { deepseek(...) }` DSL.
- **`JsonEscapeTest`** (#2378) — 10 tests covering backslash/quote, five short-form controls, every other U+0000-U+001F as `\u00XX`, printable-ASCII passthrough, DEL literal, multibyte + surrogate-pair preservation, forward-slash literal, full-BMP round-trip through `LenientJsonParser`, and realistic carrier payloads (NUL, form-feed, ESC, mixed).
- **`ToolParametersSchemaTest`** (#2377) — each of three provider clients verifies the closed fallback emits the permissive default and that `parametersSchemaJson` is forwarded verbatim when set.
- **`McpClientInputSchemaForwardingTest`** (#2377) — `toolDefs()` carries inputSchema through (with and without prefix); null when the upstream tool has no schema. End-to-end via `MockStdioMcpServer`.
- **`OllamaClientRetryTest`** (#2380) — five TDD-first tests: transient EOF retries to success, transient 500 retries, non-transient model-not-found fails fast (1 attempt), non-transient capability mismatch does not enter the retry loop, persistent transient exhausts retries at exactly maxAttempts=3.
- **`ClaudeClientChatStreamLiveTest`** — extended prompt to "1..50" so Haiku reliably emits >= 3 SSE chunks across a measurable timing gap; previous "1..10" was short enough that Haiku occasionally bundled the full reply into two same-millisecond chunks.
- **`ForumExecutionTest.antagonistic agents debate`** — Bull / Bear prompts reframed as formal-debate-exercise roles (construct strongest rhetorical case for YES / NO) so modern instruction-tuned models can play the part without being asked to assert known falsehoods.
- **`AgenticLoopTest.agent pipeline returns Int result` and `FibonacciMemoryTest.pre-seeded memory resumes`** — replaced hard assertions on LLM-quality-dependent outputs with `assumeTrue`-then-`assertEquals` pairs; the framework signal is preserved (wrong-by-framework still fails red), Ollama-quality variance becomes a skip.
- **`McpServerLifecycleTest`** (#889) — 8 new assertions covering `url`/`isRunning`/`stop` lifecycle invariants. Kills ~6–8 PIT mutants in `McpServer.kt:82-95` that the response-code tests couldn't reach.
- **`McpRunnerMissingFlagValueTest`** (#889) — 5 tests covering the `--port` / `--expose` missing-value error paths and multi-error accumulation.
- **`LenientJsonParserUnterminatedTest`** (#889) — 9 tests pinning the parser's "lenient on shape, strict on safety" contract: unterminated string / object / array at EOF doesn't hang; backslash-at-EOF; unicode-escape-at-EOF boundary; empty / whitespace-only / non-JSON-garbage returns null cleanly.
- **`InternalsAgentTest`** (#1837) — replaced hard-coded `63` skill-count assertion with `assertEquals(countAdjunctsOnClasspath(), agent.skills.size)`. Test no longer breaks when adjuncts are added.

## [0.5.0] — 2026-05-16

The platform release. Streaming runtime end-to-end, MCP-as-skills unification, every composition operator surfacing typed event flows. v0.4.x was about correctness (typed boundaries, KSP, reflect-optional); v0.5.0 is about visibility — what's happening inside an agent's loop and across the wire is now first-class.

### Added

#### Streaming runtime
- **`agent.session(input): AgentSession<OUT>`** — primary entry point for observing agent execution. Returns a cold `Flow<AgentEvent<OUT>>` of typed events plus a `suspend fun await(): OUT` terminal. Each call starts a fresh invocation; sharing across collectors is via `events.shareIn(...)`. Defined in `agents_engine.runtime.events`. Backward compat preserved — existing `agent.invoke(input)` and `agent.invokeSuspend(input)` go through the same internal path with a no-op emitter, byte-for-byte unchanged behavior.
- **`AgentEvent<OUT>` sealed hierarchy** — eight subtypes covering the full lifecycle: `Token(skillName, text)`, `ToolCallStarted(callId, toolName)`, `ToolCallArgumentsDelta(callId, deltaJson)`, `ToolCallFinished(callId, toolName, arguments, result, isError)`, `SkillStarted(skillName)`, `SkillCompleted(skillName, tokensUsed)`, `Completed<OUT>(output, tokensUsed)`, `Failed(cause)`. Every event carries `agentId` so consumers can demultiplex composed streams. Only `Completed<OUT>` is parameterized on the typed output; the rest are `AgentEvent<Nothing>` and flow through any `AgentSession<OUT>`.
- **`ModelClient.chatStream(messages): Flow<LlmChunk>`** as a default-implementing sibling of `chat`. Non-streaming providers keep working unchanged; the default wraps `chat()` and emits a chunk-equivalent sequence.
- **`LlmChunk` sealed type** — provider-level chunks: `TextDelta`, `ToolCallStarted`, `ToolCallArgumentsDelta`, `ToolCallFinished`, `End(tokenUsage)`. Sits between adapters and `chatOrStream`, keeping provider quirks from leaking into `AgentEvent`.
- **Cumulative `TokenUsage` on `SkillCompleted` and `Completed`** — summed across every LLM turn of one skill invocation (prompt and completion tokens summed independently). Null for `implementedBy` skills (no LLM round-trip).

#### Native streaming adapters
Three adapters override the default `chatStream` with real wire-level streaming:
- **Ollama (NDJSON)** — `POST /api/chat` with `stream: true`. Line-by-line parser; tool calls land in the final chunk (Ollama limitation), emitted as the canonical `ToolCallStarted` / `ArgumentsDelta` / `ToolCallFinished` triple. Live integration: ~19 chunks per response, measurable timing gap between first and last.
- **Anthropic SSE** — `POST /v1/messages` with `stream: true`. Indexed content-block aware: tracks `Map<Int, BlockState>` so interleaved `content_block_delta` events for text + tool_use can be routed to the right block. `tool_use` blocks carry the canonical Anthropic `toolu_*` id; we use it verbatim as `LlmChunk.ToolCallStarted.callId` (the case `ToolCall.callId` was designed for). Live integration verified against `claude-haiku-4-5-20251001`.
- **OpenAI SSE** — `POST /v1/chat/completions` with `stream: true` + `stream_options.include_usage: true`. Per-index tool-call state (id from first delta, args accumulated across deltas). Terminator: `data: [DONE]`. Live integration verified against `gpt-4o-mini`.

Cancellation contract verified by regression-guard tests on all three adapters: Kotlin Flow's channel-backed `emit` propagates collector cancellation back through `useLines` + `.use { stream }`, closing the underlying InputStream before the next blocking read.

#### Composition session support
Every composition operator now exposes a `.session(input)` entry point. Inner events from each contained agent flow with their own `agentId`s; the operator emits a single terminal `Completed`/`Failed`:

- **`Pipeline.session(input)`** (#1745, #1746) — sequential composition. Each stage runs to completion (streaming its tokens), then the next starts with the typed `MID` value. Three-stage chains (`a then b then c`) emit events from all three.
- **`wrap` (`teacher wrap student`)** (#1747) — teacher streams; its output becomes the student's prompt override; student streams. Consolidated `invokeSuspendForSession` to take an optional `promptOverride`, collapsing two near-identical entry points.
- **`Branch.session(input)`** (#1748) — source agent streams, matched route streams. `BranchRoute` gains `sessionExecutor` and `routedAgentName` so terminal `Completed.agentId` points at the agent that actually produced the output.
- **`Loop.session(input)`** (#1749) — bracket events emitted per iteration; same `agentId` repeated each iteration.
- **`Parallel.session(input)`** (#1750) — branches run concurrently on `Dispatchers.Default`; their events interleave by arrival order in the shared Flow, demultiplexable by `agentId`. Terminal `Completed.agentId = "parallel"`.
- **`Forum.session(input)`** (#1751) — participants stream concurrently, captain streams sequentially after. Preserves the `ForumReturnException` short-circuit.
- **`Swarm.absorb(sibling)`** (#1752) — absorbed siblings stream their inner events into the captain's session, between the captain's own `ToolCallStarted` and `ToolCallFinished` brackets. `ToolDef` gains an optional `sessionExecutor` channel that any future sub-agent-wrapping tool can use.

#### MCP-as-skills unification
The conceptual point of v0.5.0: an MCP capability and an agent `Skill` share the same shape (named, described, typed unit of work). All three MCP capability surfaces now expose as `Skill<Map<String, Any?>, String>`:

- **`mcp.toolSkills()`** (#1795) — every MCP-exposed tool wrapped as a Skill whose `implementedBy` invokes `mcp.call(toolName, args)`. Sits alongside the existing `mcp.toolDefs()` (tools as auxiliary functions a skill calls); consumers pick the shape that matches their agent design.
- **`mcp.promptSkills()`** (#1796) — every server-side prompt template wrapped as a Skill whose `implementedBy` invokes `mcp.getPrompt(name, args)`. New `McpClient.listPrompts()` and `McpClient.getPrompt(name, args)` methods.
- **`mcp.resourceSkills()`** (#1810) — every URI-addressable resource wrapped as a Skill whose `implementedBy` invokes `mcp.readResource(uri)`. Skill args are ignored — the URI is captured in the skill's closure. New `McpClient.listResources()` and `McpClient.readResource(uri)` methods.

`McpServer` gains DSLs for the server side:
```kotlin
McpServer.from(agent) {
    port = 0
    expose("skill-name")                                          // tool (existing)
    prompt("greet", "Greeting template") { args -> "Hello ${args["name"]}" }  // new
    resource("policy:///precision.md", "precision-policy",
             description = "...", mimeType = "text/markdown") {   // new
        "Be precise. Cite sources."
    }
}
```
Handlers added for `prompts/list`, `prompts/get`, `resources/list`, `resources/read`. Initialize capabilities now declare prompts and resources when registered.

- **`McpClient.snapshot: McpServerInfo`** (#1734) — immutable view of the connected server's full surface (identity, capabilities matrix, tools, prompts, resources, resource templates). Populated after `handshake()` + `loadTools()`.

#### Test infrastructure
- **Loopback MCP fixture (`LoopbackMcpAlgebraTest`, #1754)** — agent → `McpServer.from(...)` → `McpClient.connect(server.url)` → tool invocation, all in-JVM. Round-trip verified by computing `sqrt(π/e)` (digits-as-arrays + BigInteger) and checking the result with both a Math.sqrt sanity floor and a BigDecimal square-back proving `result² ≈ π/e` to 20 decimal places.
- **Three pre-existing MCP tests converted to loopback** (#1794) — no more `MCP_REDMINE_URL` requirement. `./gradlew mcpIntegrationTest` runs fully out of the box.
- **`./gradlew testAll`** task (#1720) aggregates unit + KSP + no-reflect smoke + live-llm integration + live-mcp integration into one command for pre-push verification.
- **`docs/streaming.md`** (#1744) — consumer guide for the session API, native streaming status, cancellation contract, test coverage map, composition note.
- **`docs/premortem-0.5.0-streaming.md`** (#1721) — design-before-code premortem listing the typed event hierarchy, cancellation contract, composition fidelity matrix, success criteria. Every claim in this release notes points at a criterion this premortem listed.

### Roadmap updates
- **Sandboxed tool execution** refined in `docs/roadmap.md` Phase 3 with concrete backends: `ProcessSandbox` (Seatbelt on macOS, bwrap on Linux), `WasmSandbox` (Chicory pure-Java), `DockerSandbox` (docker-java extras module). Scoped to subprocess-shaped tools only — `grants { }` covers in-process lambdas.
- **Multimodal I/O** added — image/audio input (Phase 2) via `LlmContent` sealed-block evolution of `LlmMessage`; image generation (`ImageModelClient`) and TTS (`TTSModelClient`) in Phase 3.
- **HTTP `sendAsync` migration** documented as the cancellation latency optimization deferred past v0.5.0 — correctness already holds via Flow semantics (verified by adapter regression-guard tests); `sendAsync` would tighten mid-line cancellation but is not blocking.

### Migration notes
v0.5.0 is **drop-in for v0.4.6** consumers. Every existing API still works:
- `agent.invoke(input)` and `agent.invokeSuspend(input)` unchanged.
- `agent.observe { PipelineEvent -> ... }` unchanged (the v0.4.x event surface for post-hoc skill/tool/error observability).
- `model { ollama / claude / openai }` adapters unchanged; `chatStream` is a default-impl addition.

To opt into streaming:
```kotlin
val session = myAgent.session(input)
session.events.collect { event -> /* render Token, log ToolCall*, ... */ }
val output: OUT = session.await()  // typed terminal
```

To consume an MCP server via the unified surface:
```kotlin
val mcp = McpClient.connect(url)
val agent = agent<Map<String, Any?>, String>("wrapper") {
    skills {
        mcp.toolSkills().forEach { +it }
        mcp.promptSkills().forEach { +it }
        mcp.resourceSkills().forEach { +it }
    }
}
```

### Stats
- **1,074+ unit tests** across root + KSP + no-reflect smoke subprojects — 0 failures
- **54 live-LLM integration tests** — green on clean runs against `gpt-oss:120b-cloud`, `claude-haiku-4-5-20251001`, `gpt-4o-mini`
- **7 live-MCP integration tests** — fully self-contained loopback coverage, no external infrastructure
- v0.4.6 → v0.5.0: ~30 commits, ~25 new test files

## [0.4.6] — 2026-05-15

Follow-up to v0.4.5's open thread: actually make `kotlin-reflect` optional at runtime, and ship the smoke test that proves it. The premortem (`docs/premortem-0.4.6.md`) defined the success criteria; this release meets them.

### Changed
- **`kotlin-reflect` is now `compileOnly` for real.** v0.4.5 reverted to `implementation` honestly because several callsites (`Skill.toLlmDescription`, `ToolDef` typed-tool validation, `McpServer` `@Generable` input detection, `GenerableSupport.toLlmInput` + `generableToJson`, `BranchBuilder.sealedSubclasses`, `fromLlmOutput`'s `isSealed` check) still went directly through `kotlin.reflect.full.*`. v0.4.6 wraps every remaining callsite via `ReflectionFallback.withReflection { ... }` or routes through the new `hasGenerableAnnotation()` probe which checks the KSP-generated cache first. The published POM now ships `kotlin-reflect` as `compileOnly` — consumers either apply `:agents-kt-ksp` (recommended, full functionality) or pull `kotlin-reflect` in themselves (legacy reflection paths). Without either, the runtime degrades to sane fallbacks (empty schema, simple-name LLM description, null on `constructFromMap`) instead of crashing.
- **`ReflectionFallback` catches `KotlinReflectionNotSupportedError` in addition to `LinkageError`.** kotlin-stdlib's `KClass::isSealed` doesn't throw `NoClassDefFoundError` when reflect is absent — it throws its own `kotlin.jvm.KotlinReflectionNotSupportedError`, a sibling under `Error`. Both branches are now caught.
- **`fromLlmOutput` no longer crashes on the `isSealed` check without reflect.** The `if (isSealed)` dispatch is now wrapped — data classes route through the unguarded `constructFromMap` path (cache hit returns instantly; cache miss falls into the wrapped reflection branch), sealed roots without reflect return null cleanly.

### Added
- **`agents-kt-no-reflect-test` Gradle subproject.** Excludes `kotlin-reflect` from its consumer-shaped classpaths (`compileClasspath`, `runtimeClasspath`, and the test counterparts — scoped narrowly so the Kotlin compiler daemon's own classpath is untouched, since the compiler internally uses reflect to read its argument metadata). The suite asserts (a) `Class.forName("kotlin.reflect.full.KClasses")` throws `ClassNotFoundException` — the proof that reflect really is absent; (b) `jsonSchema`, `toLlmDescription`, `fromLlmOutput` all return correct results via the generated `__GeneratedSchema` companion when present; (c) all three return their graceful-degradation fallbacks when no companion exists. Failing this suite regresses the v0.4.6 contract.

## [0.4.5] — 2026-05-14

Patch release responding to v0.4.4 reviewer feedback (#1707). All five concerns verified against `main`; correctness fixes shipped, one over-promise walked back honestly.

### Fixed
- **`wrap` is now race-safe under concurrent invocation.** v0.4.4 implemented `teacher wrap student` by mutating `student.prompt` for the duration of one call and restoring in `finally`. Single-placement protected against multi-pipeline reuse but not against the same Pipeline launched from multiple coroutines, or a direct invocation racing with a wrap-pipeline mid-call — one lane could see another's system prompt. v0.4.5 threads the effective prompt through `executeAgentic(agent, skill, input, effectivePrompt)` as a local parameter; `agent.prompt` is never mutated. Test coverage: `WrapConcurrencyTest` exercises 8 parallel lanes with distinguishable teacher outputs + a direct invocation racing alongside, asserting no cross-talk. **Consumer-visible behavior change**: `wrap`'s prompt override is only visible to **agentic skills** (those that go through `executeAgentic`). `implementedBy` skills don't see it (and never reliably did — the old test pattern that relied on reading `agent.prompt` from inside an `implementedBy` lambda worked only because of the now-removed mutation race). Realistic usage of `wrap` is for LLM-driven students; that path is unchanged. (#1707/#3)
- **KSP `constructFromMap` no longer emits uncompilable nested references.** v0.4.4 generated `Customer__GeneratedSchema.constructFromMap(it)` for every nested `@Generable` ref. If the nested class had default-valued primary-ctor params, the processor skipped emitting its `constructFromMap` — leaving the outer class's generated source with an unresolved reference at compile time, not a runtime fallback as the code comment claimed. v0.4.5 routes nested refs through `<NestedClass>::class.constructFromMap(map)` instead: the `::class` receiver is a Kotlin class literal (no `kotlin-reflect` involvement at the call site), and the `@PublishedApi` extension's cache lookup handles both cases — generated companion present → fast path; absent → reflection fallback or graceful null. Test coverage: `ConstructFromMapEmitterTest` pins the new emission shape. (#1707/#2)

### Changed
- **`kotlin-reflect` reverted from `compileOnly` back to `implementation`.** v0.4.4 framed the KSP arc as "reflect-free runtime", but several hot paths still call `kotlin.reflect.full.*` regardless of KSP: `Skill.toLlmDescription`, `AgenticLoop` system-message build, `ToolDef` typed-tool validation, `McpServer` runtime-discovered `@Generable` input detection, `GenerableSupport.toLlmInput`, `BranchBuilder.sealedSubclasses`. A consumer without `kotlin-reflect` would hit `LinkageError` at agent construction, not just at LLM calls. The KSP wins are still real — `jsonSchema`, `toLlmDescription`, and `constructFromMap` read-paths skip the reflection walk when a generated companion exists — but the runtime continues to require `kotlin-reflect`. A future PR will wrap each remaining callsite and ship a consumer-app smoke test without `kotlin-reflect`; too large to be a v0.4.5 patch. (#1707/#1)
- **Doc drift cleared.** README's "main prepared as 0.4.3" stale string updated to current version. `wiki/API-Quick-Reference.md`'s `maxTurns` default corrected from `Int.MAX_VALUE` to the actual code default `8` (set in `BudgetConfig.kt`). (#1707/#5)

### Deferred to a follow-up commit
- **CI alignment with the wrapper** (`./gradlew` at Gradle 9.5.0 instead of action-supplied 8.13) — patch ready locally; requires a GitHub token with `workflow` scope to push. See #1707/#4 follow-up.

## [0.4.4] — 2026-05-13

First Maven Central release after **v0.4.2**. Internal tags `v0.4.3` (BC pin completeness) existed on GitHub but never reached Maven Central; their content is folded into 0.4.4 alongside the KSP arc and the `wrap` operator. Skip straight to 0.4.4:

```kotlin
implementation("ai.deep-code:agents-kt:0.4.4")
```

## [0.4.3-unpublished] — 2026-05-12

### Added
- **KSP validation pass for `@Generable`** — the `:agents-kt-ksp` skeleton that's shipped since 0.3.0 (#1018) is now wired to do real work. The processor walks every `@Generable` class in the consumer's compilation and emits compile-time errors for shapes the framework can't actually construct from JSON: non-sealed interfaces, annotation classes, enums, abstract classes, and classes without a parameterised primary constructor. Sealed types short-circuit — they route through the polymorphic / `type` discriminator path that `GenerableSupport.sealedJsonSchema` already handles. Errors point at the offending declaration so the IDE shows red squiggles where the user can fix them. Schema-generation pass (replacing runtime reflection) is the next KSP increment; this issue closes the validation half (#1700).
- **KSP schema-generation pass** — `:agents-kt-ksp` now emits `<ClassName>__GeneratedSchema.kt` for every non-sealed `@Generable data class` whose fields are all representable types (String / Int / Long / Double / Float / Boolean / `List<T>` / nested `@Generable`). The generated file holds a `const val JSON_SCHEMA: String` byte-identical to what `KClass.dataClassJsonSchema()` produces via reflection. The runtime's `KClass.jsonSchema()` (in `GenerableSupport`) tries `Class.forName("${qualifiedName}__GeneratedSchema")` first — hit → returns the constant, cached for the JVM lifetime; miss → falls through to the existing reflection path. Consumers without KSP applied see no behavior change. Consumers with KSP get: ~50-200ms cold-start saving, zero per-call reflection on the schema path, byte-stable schemas across JVM restarts (deterministic Anthropic prompt-cache hits), and the prerequisite for dropping `kotlin-reflect` from the runtime classpath. Lifted alongside: field-type validation — fields with types outside the supported set (e.g. `java.time.Instant`) now fail at compile time pointing at the offending param, instead of silently degrading to `{"type":"string"}` at runtime (#1701).
- **KSP sealed-root schema generation** — extends #1701 to `@Generable sealed interface` / `sealed class` types. Walks the parent's `getSealedSubclasses()` at compile time and emits a `{"oneOf":[...]}` schema where each variant carries a `"type":"<SimpleName>"` discriminator at the head, then the variant's own primary-ctor params, then `additionalProperties:false`, then a trailing `description` field if the variant class carries `@Guide`. Byte-identical to `GenerableSupport.sealedJsonSchema()` + `variantJsonSchema()`. Cross-module sealed hierarchies (variant declared in a different module than the parent) currently produce an incomplete generated schema — single-module is the common case and works; cross-module is a known follow-up. The runtime `KClass.jsonSchema()` is now fully shape-agnostic — the gate that skipped lookup for sealed roots in #1701 is removed (#1702).
- **KSP `toLlmDescription` codegen** — next-frequency runtime read after `jsonSchema` (one call per skill on every agent build, embedded in the system prompt). Each `<ClassName>__GeneratedSchema.kt` now carries a second `const val LLM_DESCRIPTION: String` alongside `JSON_SCHEMA`, byte-identical to what `GenerableSupport.dataClassLlmDescription()` / `sealedLlmDescription()` produce. Class-level `@Generable(description)` renders as the intro paragraph; field `@Guide(description)` becomes the `: description` tail on bullets; variant-class `@Guide` becomes the `: description` tail on `### Variant` headers. `@LlmDescription(text)` overrides the auto-generated text — the override is baked into the constant verbatim so the runtime lookup stays reflection-free either way. The runtime cache (renamed `GeneratedMetaCache`) now loads ALL `public static final String` fields from the generated object in one pass, exposing typed `lookupJsonSchema` / `lookupLlmDescription` methods; future constants (`constructFromMap` next) join without touching the cache implementation. Consumers without KSP still hit the reflection fallbacks unchanged (#1703).
- **KSP `constructFromMap` codegen** — last `kotlin-reflect` user for `@Generable` typed-tool args is now compile-time. Each generated companion gains a `@JvmStatic fun constructFromMap(fields: Map<*, Any?>): Foo?` that calls into freshly-exposed `@PublishedApi internal` coercion helpers (`coerceString`, `coerceInt`, `coerceLong`, `coerceDouble`, `coerceFloat`, `coerceBoolean`, `coerceList`) — the same strict overflow / type rejection the reflection path enforces (#665, #855). Sealed roots dispatch by `"type"` discriminator to each variant's own generated `constructFromMap`. Cache extended with `lookupConstructor(KClass)` that resolves the JVM method via JDK reflection (`java.lang.reflect`, not `kotlin-reflect`) and caches the invocation lambda. **Scope:** generation skips data classes with default-valued primary-ctor params — those need the Kotlin compiler's synthetic constructor-with-mask which isn't callable from generated Kotlin source; reflection still handles those. Sealed-variant subclasses with the right shape get full generated path. With this in, every reflection-walk hot path on `@Generable` has a codegen alternative; Phase 3 (dropping `kotlin-reflect` from runtime classpath) becomes a follow-up POM-only change (#1704).

### Changed (potential consumer impact)
- **`kotlin-reflect` is no longer on the runtime classpath of `ai.deep-code:agents-kt`.** With every `@Generable` hot path (`jsonSchema`, `toLlmDescription`, `constructFromMap`, sealed-variant dispatch) replaceable by KSP-generated code (#1701-#1704), the reflection paths are now `compileOnly` fallbacks. Consumer migration paths: (a) apply `:agents-kt-ksp` (recommended — generated path covers everything); (b) add `org.jetbrains.kotlin:kotlin-reflect` to your own dependency declarations if you want the reflection fallback to remain available. **Without either**, reflection-using fallbacks return null gracefully via the new `ReflectionFallback.withReflection { ... }` wrap — typed-tool deserialization routes through `onError.invalidArgs`, schema/description lookups return placeholder shapes — so consumers don't crash but they will see degraded LLM output. **Defensive emission gate** added alongside: sealed `@Generable` parents whose variants aren't visible to KSP (incremental-compile race, edge cases) skip schema emission; reflection takes over at runtime against the full JVM hierarchy. Both pieces shipped together in #1705.
- **`wrap` operator (PRD `>>`)** — closes the last open Phase 1 PRD line item. `teacher wrap student` returns a `Pipeline<IN, OUT>` that runs the teacher first to compute a system prompt string, then invokes the student with that string as its prompt for that one call. The student's baked-in `prompt` is restored after the call returns. Two framings: **education** (teacher specializes a generalist student) and **security** (teacher locks down the student's task surface). Type: `Agent<IN, String> wrap Agent<IN, OUT>` → `Pipeline<IN, OUT>`. Headline test: agent A teaches agent B to compute `fib(10)` via a `fib` tool, driven by a stub `ModelClient` that reads the teacher's instruction from the system prompt and emits a tool call. Both agents participate in the single-placement contract via the returned Pipeline (#1698).

### Security
- **Complete the BouncyCastle pin across both Gradle modules.** v0.4.2 added explicit BC 1.84 `compileOnly` declarations + `force(...)` to the root `build.gradle.kts`, but missed the `:agents-kt-ksp` subproject — its `kotlinBouncyCastleConfiguration` still pulled BC 1.80 transitively, which is what kept the four dependabot advisories alive. v0.4.3 mirrors the same fix into `agents-kt-ksp/build.gradle.kts` and prunes stale 1.80 entries from `gradle/verification-metadata.xml`. Both `gradle.lockfile` files now record 1.84 everywhere; no 1.80 entries remain anywhere in the repo. Published JARs are unchanged — BC was never in `runtimeClasspath` for either module.

## [0.4.2] — 2026-05-12

### Security
- **Make BouncyCastle 1.84 pin visible to Dependabot.** The existing `force(...)` block in `build.gradle.kts` already pins BC to 1.84 (the patched release per OSV + GHSA) and the lockfile + verification metadata confirm 1.84 is what resolves. However, Dependabot's submitted dependency graph reads *requested* versions, not resolved, and was still alerting on the 1.80-range CVEs that don't apply to our build. Declare BC 1.84 explicitly at the project level via `compileOnly(...)` so Dependabot sees the explicit 1.84 nodes. `compileOnly` does NOT ship to consumers and does NOT add to the runtime jar — `runtimeClasspath` stays free of BC, as before. No functional change for downstream users.

## [0.4.1] — 2026-05-12

Dependency refresh on top of the v0.4.0 feature set. v0.4.0 was tagged on GitHub but never reached Maven Central; **0.4.1 is the first published release of the three-providers feature set**.

### Security
- **Refreshed runtime + build dependencies** to close the four dependabot advisories on `main`:
  - `kotlinx-coroutines-core` and `kotlinx-coroutines-test` 1.10.2 → 1.11.0
  - Gradle wrapper 9.4.1 → 9.5.0
  - Lockfile and `gradle/verification-metadata.xml` regenerated.
  - Supersedes the open dependabot PRs (#47, #48, #39).

### Compatibility
Source-compatible with 0.4.0. Consumers on 0.3.x can upgrade straight to 0.4.1 — same surface as 0.4.0, plus the dep refresh.

## [0.4.0] — 2026-05-12

Three model providers, fail-fast startup, and a long-overdue bugfix.

### Binary compatibility
**Source-compatible** with 0.3.x — every new public API addition (`claude()`, `openai()`, `ModelProvider.ANTHROPIC`, `ModelProvider.OPENAI`, the `precheck` hook, the new `ModelConfig` fields) has defaults; existing 0.3.x code compiles unchanged.

**Wire-shape change for Ollama tool-call messages** (#1694) — assistant turns with `tool_calls` and no textual content now serialize as `content: null` on the wire instead of `content: ""`. This is purely a payload-shaping change; in-memory `LlmMessage` is unchanged. Local Ollama tolerated both shapes; Ollama Cloud's strict validators only accept the new (spec-compliant) form.

### Added
- **Anthropic Claude adapter** — new `ClaudeClient: ModelClient` and `model { claude("claude-opus-4-7"); apiKey = "..." }` DSL. Maps the framework's `LlmMessage` / `LlmResponse` model to Anthropic's structured Messages API content blocks (`tool_use` / `tool_result`); tools advertise as `input_schema` (Anthropic's spelling); top-level `error` envelopes surface as `LlmProviderException`, same boundary contract as `OllamaClient` (#702). Provider dispatch in `AgenticLoop` constructs the client lazily so the agent's full tool catalog flows in. Live integration tests against the real API gated on a gitignored `.secrets/anthropic-key` (with `ANTHROPIC_API_KEY` env fallback); skipped via JUnit `Assumptions` when the key is absent (#1644).
- **OpenAI Chat Completions adapter** — `OpenAiClient: ModelClient` and `model { openai("gpt-4o"); apiKey = "..." }` DSL. Maps to OpenAI's `tool_calls` / `tool_call_id` shape with synthesized ids paired FIFO per request; `function.arguments` rides the wire as a stringified JSON (OpenAI's convention); tools advertise as `parameters` (vs Anthropic's `input_schema`). System messages stay in the messages array (vs Anthropic's hoisted top-level field). Same provider-error contract via `LlmProviderException`. Live integration tests gated on `.secrets/openai-key` (with `OPENAI_API_KEY` env fallback) (#1656).
- **`LiveRunner` precheck hook** — `LiveShowBuilder.precheck: (() -> Unit)?` runs after arg-parse and before banner / `--once` / REPL. Throw to abort startup; the runner prints `error: <msg>` and returns exit code 2. New `OllamaPreflight(host, port)` helper performs a `GET /api/tags` reachability check; wired into the swarm-demo captain so a misconfigured endpoint fails fast at startup instead of mid-spinner on the first turn (#1132).
- **Live typed-args integration tests across all three providers** — `TypedArgsLiveIntegrationTest` exercises the full `@Generable` schema → provider envelope → wire → response parse → `KClass.constructFromMap` → typed executor round-trip on Ollama / Claude / OpenAI. Each test skips cleanly when the relevant provider isn't reachable (#1675).

### Changed
- `ModelProvider` enum gained `ANTHROPIC` and `OPENAI`. `ModelConfig` carries optional `apiKey`, `anthropicBaseUrl`, `openAiBaseUrl`, and `maxTokens` fields used by the Claude / OpenAI adapters. Default Ollama path is unchanged.

### Fixed
- **OllamaClient: assistant tool-call messages now wire-serialize `content` as JSON `null`** when no textual content accompanies the `tool_calls`. The previous shape (`content: ""`) was tolerated by local Ollama but rejected with `500 Internal Server Error` by Ollama Cloud's strict OpenAI-compatible validators (`gpt-oss:120b-cloud`, `gpt-oss:20b-cloud`). This broke every multi-turn agentic loop against those models. The null-coercion fires only when role is `assistant` AND `tool_calls` is non-empty AND content is blank — empty-string assistant turns without tool_calls keep their previous shape. Other adapters (`ClaudeClient`, `OpenAiClient`) were already spec-compliant; this is an Ollama-only fix (#1694).

### Security
- **`ModelConfig.toString()` masks `apiKey`** as `<6-char-prefix>…<N>chars` so `log.info("config = $cfg")`, future reflection-based serializers, or stack traces that capture a config no longer leak credentials. `equals`/`hashCode` still consider apiKey — masking is observation-only (#1665).
- `SECURITY.md` extended with a "Handling LLM provider credentials" section: `.secrets/` directory convention, `chmod 0600/0700` guidance, the `toString` masking contract, header-handling claim, and a "if a key is committed → rotate first" runbook.

## [0.3.0] — 2026-05-05

First leg of the **KSP / compile-time-validation initiative** described in `docs/ksp-design.md`. This release ships **typed tool refs** — Kotlin's type system catches `tools("typo")` mistakes that previously bombed at agent `validate()` (or in CI test runs). Plus the `:agents-kt-ksp` module skeleton, ready for the Phase 2 codegen work.

### Binary compatibility

**Source-compatible** with 0.2.x — your code compiles unchanged (you'll see deprecation warnings on `tools("name")` calls, with a `ReplaceWith` hint to the typed form).

**NOT binary-compatible.** `tool(...)` builders changed return type `Unit → Tool<Args, Result>`. Consumers who upgrade the `agents-kt` jar without recompiling will hit `NoSuchMethodError` at first tool registration. Recompile against 0.3.0; no source changes required. If you depend on `agents-kt` from a published library, that library must also republish against 0.3.0. This is why the bump goes 0.2.x → 0.3.0 and not 0.2.x → 0.2.3.

### Added
- `Tool<Args, Result>` typed handle returned by every `tool(...)` builder overload. Phantom-typed wrapper around `ToolDef` whose type parameters propagate through the agent build (#1015).
- `Skill.tools(first: Tool<*, *>, vararg rest: Tool<*, *>)` — typed overload alongside the legacy stringly-typed form. Tool typos become red squiggles in IntelliJ instead of runtime errors at `validate()` (#1016).
- `Skill.tools()` — explicit no-argument overload that marks a skill agentic with no allowlisted tools (the model gets only memory + built-in tools). Disambiguates from the deprecated string-vararg form.
- `docs/ksp-design.md` — initiative roadmap, runtime-checks inventory (72 sites bucketed), three-phase plan.
- **`:agents-kt-ksp` Gradle module** — new sibling artifact `ai.deep-code:agents-kt-ksp` published to Maven Central. Empty `SymbolProcessorProvider` skeleton; consumers can wire it via `ksp("ai.deep-code:agents-kt-ksp:VERSION")` but it does no work yet. Phase 2 of the KSP initiative (#1018). The validation pass (#1019) and schema-generation pass (#1020) plug into the processor in subsequent issues.
  - Multi-module Gradle setup: `settings.gradle.kts` includes `:agents-kt-ksp`; same Maven Central + Sonatype publishing wiring as the runtime artifact; same in-memory PGP signing.
  - Depends on `com.google.devtools.ksp:symbol-processing-api:2.3.7` (KSP2, decoupled from Kotlin compiler version).
  - Reads runtime annotations via `compileOnly(project(":"))` — never lands on the consumer's runtime classpath.

### Changed
- README + `docs/model-and-tools.md` examples now show typed-ref form first; string form is documented only for built-in tools (`escalate`, `throwException`, `memory_*`).
- Internal test fixtures migrated to typed refs across 35+ files (#1017).

### Deprecated
- `Skill.tools(vararg names: String)` — soft-deprecated at warning level. Stays for built-in tools (`escalate`, `throwException`, `memory_*`) and runtime-discovered tool names (MCP); no removal planned pre-1.0.

## [0.2.3] — 2026-05-04

Hotfix patch — single bug.

### Fixed
- `LenientJsonParser` no longer infinite-loops / OOMs on input where a JSON array or object contains a non-numeric, non-string, non-keyword character (e.g. `[abc]`, `{"k": foo}`, `[<html>]`). The previous `parseValue()` fell through to `parseNumber()` for any unrecognized character; `parseNumber()` returned 0 without advancing `pos`, so `parseArray()` / `parseObject()` spun forever, accumulating zeros until the heap was exhausted. The 0.2.2 `MAX_NESTING_DEPTH` guard (#854) only caught deep nesting, not zero-progress in a single loop body. Two-layer fix: `parseValue()` is now strict on the `else` branch (throws on unknown chars; the throw is caught by the top-level `parse(input)` try/catch and returns `null`, preserving the lenient contract); `parseArray()` and `parseObject()` carry zero-progress guards as defense-in-depth (#1028).

### Trigger path in the wild
Any LLM response or HTTP body containing `[…non-JSON content…]` would hit this — including non-Ollama responses on `localhost:11434` (HTML error pages, JSON error blobs with embedded brackets), small-model output that emitted markdown tables or pseudo-JSON, or test fixtures pointing the agent at unrelated services. Surfaced as `OutOfMemoryError` during agent invocation, several seconds after the request started.

## [0.2.2] — 2026-05-03

A feature-heavy patch release — REPL deployment, multi-agent JAR composition (Swarm), four new observability hooks, two new budget controls, classpath-resource prompt loading, and a slimmer README. Pre-1.0 patch bump — no breaking changes; all existing API surface preserved.

### Highlights

- **`LiveShow` / `LiveRunner`** — REPL deployment surface mirroring MCP's two-layer split (`LiveShow.from(x).start()` / `LiveRunner.serve(x, args)`). Six factory overloads cover `Agent` / `Pipeline` / `Forum` / `Parallel` / `Loop` / `Branch` — any `String`-input structure becomes interactively chattable. ANSI color theme, full-resolution ASCII Agents.KT banner, in-place cat spinner during inference, lifecycle hooks (`onTurnStart` / `onTurnEnd` / `onErrorReported`), `renderOutput` post-processor, string-concatenated conversation history with `--- user ---` / `--- assistant ---` delimiters, slash commands (`/quit`, `/clear`, `/help` plus user-extensible `slash(name) { }`), `--once "<prompt>"` for non-interactive single-turn use.
- **Swarm — multi-agent JAR composition.** Drop sibling agent JARs into a folder, ServiceLoader-discover them, `me.absorb(sibling)` exposes each as a tool with full agent personality preserved (prompt, skills, knowledge, memory, observability hooks). In-JVM, no IPC overhead, no static-typing-across-JARs limitation MCP-stdio would impose. Captain-capable: any agent JAR can be elected by running its `main`.
- **Four new observability hooks.** `onError { Throwable }` for infrastructure failures (LLM transport, parse, budget). `Agent.observe { event }` bridges the four legacy hooks into one sealed `PipelineEvent` stream. `onBudgetThreshold(threshold) { reason, used }` fires once per `BudgetReason` when cumulative usage crosses a fraction (pre-cap warning). `LiveShow.onTurnStart` / `onTurnEnd` / `onErrorReported` for REPL-side telemetry.
- **Two new budget controls.** `maxTokens` (cumulative across turns when the provider reports usage; new `BudgetReason.TOKENS`) and `maxConsecutiveSameTool` (catches LLM retry loops on a broken tool; new `BudgetReason.CONSECUTIVE_TOOL`). `LlmResponse.tokenUsage: TokenUsage?` — Ollama's `prompt_eval_count` + `eval_count` plumbed through the agentic loop.
- **`loadResource(path)` for classpath prompts.** `prompt(loadResource("prompts/coder.md"))` loads UTF-8 from the classpath; fail-fast at agent construction with a helpful error if the path is missing. `loadResourceOrNull(path)` for the optional case.
- **README split.** Down from 1243 → 203 lines. Topical sections moved to `docs/{skills, model-and-tools, mcp, error-recovery, memory, generation, composition, roadmap}.md` with cross-back links.

### Added

#### REPL / runtime
- `LiveShow.from(agent | pipeline | forum | parallel | loop | branch).start().runUntilTerminated()` — programmatic REPL host. Six factory overloads collapse to one private constructor taking `suspend (String) -> Any?` (#981).
- `LiveRunner.serve(structure, args, configure)` — picocli-shaped main shim mirroring `McpRunner.serve`. Six overloads, `--once "<prompt>"`, `--max-history N`, `-h`, `-V`. JVM shutdown hook + blocking until SIGTERM, returns int exit code (#981).
- `LiveShowBuilder` configurables: `prompt`, `maxHistoryTurns`, `historyDelimiter`, `input`, `output`, plus UI polish: `colors`, `theme`, `renderOutput`, `banner`, `spinner` (#983).
- `LiveShowTheme.DEFAULT` / `LiveShowTheme.NONE` color presets binding `AnsiColor` to roles (prompt / agentOutput / error / slashOutput / banner) (#983).
- `Spinner.CAT` / `Spinner.NONE` — in-place cat-face spinner during inference, suppressed on non-TTY (#983).
- Default banner — full-resolution ASCII rendering of the Agents.KT logo (angular cat face with pink crown accents, block-letter wordmark) (#983).
- `Swarm.discover()` and `Swarm.discover(classLoader)` — ServiceLoader-walk for `AgentProvider` impls (#984).
- `interface AgentProvider { fun build(): Agent<*, *> }` — single-method SPI for sibling JARs (#984).
- `Agent<*, *>.absorb(sibling: Agent<*, *>)` — wraps the sibling as a tool on the captain; auto-enables across all skills; fails fast on name collision / typed-input siblings (#984).

#### Observability
- `Agent.onError { Throwable -> }` — infrastructure-error observability hook (LLM transport, response parse, budget). Pure observability — original exception always rethrows; listener exceptions attached as suppressed (#962).
- `Agent.observe { event -> }` — sealed `PipelineEvent` (`SkillChosen` / `ToolCalled` / `KnowledgeLoaded` / `ErrorOccurred`) bridges the four hooks into one typed stream; composes additively with prior listeners (#965).
- `Agent.onBudgetThreshold(threshold) { reason, usedPercent -> }` — pre-cap warning hook; fires once per `BudgetReason` when cumulative usage crosses the fraction (#966).

#### Budget
- `BudgetConfig.maxTokens: Int?` + `BudgetReason.TOKENS` — cumulative token cap; counts only when the provider reports `tokenUsage` on the response (#963).
- `BudgetConfig.maxConsecutiveSameTool: Int?` + `BudgetReason.CONSECUTIVE_TOOL` — catches retry loops on a broken tool (#969).
- `LlmResponse.tokenUsage: TokenUsage?` (`promptTokens`, `completionTokens`, `total`) — Ollama's `prompt_eval_count` + `eval_count` plumbed end-to-end (#963).

#### DX
- `loadResource(path: String): String` — read agent prompts from `src/main/resources/...`. Fail-fast at agent construction; UTF-8 decoded; leading-slash normalized (#980).
- `loadResourceOrNull(path: String): String?` — null-returning variant for optional resources (#980).
- `Agent.toString()` — single-line `Agent<NAME>` form replacing the JVM identity-hash default (#970).
- `Agent.describe(): String` — multi-line debug summary of name + OUT type, prompt (truncated at 80), model config, budget (overrides only), skills, tools, memory bank presence (#970).

### Changed

- README split from 1243 → 203 lines. Topical content moved to `docs/{skills, model-and-tools, mcp, error-recovery, memory, generation, composition, roadmap}.md`. Each new doc links back to README (#975).
- README install snippet bumped to `0.2.2`.
- LICENSE copyright + README license footer updated to `Deep-Code.AI`.
- Default LiveShow banner is the full-resolution Agents.KT logo (40-line ASCII art); replaced the small geometric placeholder shipped briefly in earlier #983 builds (banner-followup).

### Fixed

- `LenientJsonParser` exponent-sign / Long-overflow / unicode-escape edge-case coverage (#889 cluster).
- `OllamaClient.parseResponse` extracts `prompt_eval_count` + `eval_count` from response root; partial reports drop to `null` rather than half-attributing (#963).

## [0.2.0] — 2026-05-03

A substantial release covering MCP client + server, the typed tool boundary, the runtime tool-authorization model, frozen-after-construction agents, an inline-tool fallback for capability-limited models, and a cross-cutting suspend refactor. Pre-1.0 minor bump — no breaking changes; existing blocking `invoke` API preserved via `runBlocking` shim.

### Highlights

- **MCP, both directions.** Full client (`mcp { server() }` over HTTP / stdio / TCP, Bearer auth, namespaced tools) and server (`McpServer.from(agent)` exposes an agent as an MCP-conformant 2025-03-26 server, plus `McpRunner` for one-liner standalone JARs).
- **Typed tool boundary.** `tool<Args, Result>(name, description) { args -> }` with `@Generable`-derived JSON Schema, `additionalProperties: false`, sealed-discriminator validation, repaired-args revalidation.
- **Per-skill tool authorization, runtime-enforced.** The system prompt's "Available tools" listing is descriptive; the security boundary is the runtime allowlist. Unknown tool calls are rejected before execution.
- **Frozen-after-construction agents.** Skills, tools, memory, model, budget, prompt, and error handlers are immutable once `agent { }` returns. Closes the `mcp { }` post-construction registration gap that #708 caught.
- **Suspend-native framework.** Every composition operator (Pipeline, Branch, Loop, Parallel, Forum) and Agent gain `suspend fun invokeSuspend`. Existing `operator fun invoke` is now a one-line `runBlocking` shim — at the user-facing boundary only, never inside the framework.
- **Inline-tool fallback for Ollama models without native tool support.** `gemma3:4b` and similar models that reject the native `tools` field now drive transparently via inline JSON tool-call format. No more agent failures from capability mismatches.

### Added

#### MCP
- `mcp { server(name) { url = ... | command = ... | host + port = ... } }` agent DSL — three transports, namespaced tools (`server.tool`), connection at agent-build time, `mcpClients` lifecycle handle.
- `McpAuth.Bearer(token)` and `McpAuth.None` — outgoing auth thread-through.
- `McpServer.from(agent)` — exposes an agent's skills as MCP tools; explicit `tools/listChanged: false` capability declaration; `protocolVersion` constant for tracking.
- `McpRunner.main(args)` — picocli-style standalone server entry point for shipping agents as MCP services.
- Mock MCP servers (HTTP, stdio, TCP) for tests.

#### Typed tool DSL
- `tool<Args, Result>(name, description) { args: Args -> ... }` — typed args via reflection-built JSON Schema (`Args::class.jsonSchema()`); deserialization via `constructFromMap`; deserialization failures route through `onError { invalidArgs { } }` like JSON-parse failures, not `executionError`.
- `@Generable("desc")` and `@Guide("field doc")` annotations now drive the typed tool envelope (real `properties` + `required` + per-field descriptions, replacing the legacy `properties: {}, additionalProperties: true`).

#### Runtime hardening
- **Budget controls** — `budget { maxTurns; maxToolCalls; maxDuration; perToolTimeout }`, sacrificial-thread enforcement for the per-tool case (#637).
- **`ForumTranscript<IN>` deliberation pattern** — `transcriptCaptain(agent: Agent<ForumTranscript<IN>, OUT>)` — captain receives full participant outputs (#639).
- **`BranchRoute` sealed type** with `onNull` / `onElse` markers and construction-time sealed-completeness validation (#640).
- **`SkillRoute(name, confidence, rationale)`** — structured LLM router output; `skillSelectionConfidenceThreshold` (#641).
- **Untrusted tool-output wrapping** — tool results carry an envelope so the model can't impersonate framework messages (#642).
- **Reserved tool names** — `memory_read` / `memory_write` / `memory_search` cannot be shadowed by user tools (#644).
- **Fail-fast on duplicate tool names** at agent construction (#645).
- **`registerTool` freeze guard** — closes the `mcp { }` post-construction registration bypass; `registerBuiltInTool` and `unregisterTool` remain unguarded for Forum's runtime captain rotation (#708).
- **Strict typed args** — `additionalProperties: false`; sealed `type` discriminator must match the constructed variant; `constructFromMap` rejects extra keys (#661, #665, #699).
- **Repaired args revalidation** — repaired tool args are re-validated through the typed schema before reaching the executor (#658).
- **Encapsulation** — `Agent.toolMap` and `Agent.skills` are read-only `Map` views; mutation only via DSL or framework-internal escape hatches (#659, #667).
- **`Skill.implementation` private setter** (#698).
- **Skill freeze** at end of validate() (#668).

#### Provider integration
- **`LlmProviderException`** — provider-boundary errors (auth, model-not-found, capability mismatch) surface distinctly from output-parse errors. Stops Ollama `{"error":"..."}` envelopes from flowing into user `transformOutput` as opaque text (#702).
- **Inline tool-call fallback** — when Ollama responds with `does not support tools`, `OllamaClient.chat` strips the native `tools` field, injects the tool catalog into a system message in inline JSON format, and retries. Per-instance `@Volatile` latch skips the native attempt on subsequent calls. Existing user system message preserved (#706).

#### Suspend refactor
- `suspend fun invokeSuspend(input)` on `Agent`, `Pipeline`, `Branch`, `Loop`, `Parallel`, `Forum`. Internal cross-calls go through suspend; the framework no longer wraps `runBlocking` around itself (#638).
- `AgenticLoop.executeAgentic` and `selectSkillByLlm` are now suspend; `client.chat(...)` wrapped in `withContext(Dispatchers.IO)` so cancellation interrupts the HTTP I/O thread.
- `Parallel` and `Forum` use `withContext(Dispatchers.Default)` + `coroutineScope` instead of `runBlocking(Dispatchers.Default)` — caller controls the parent scope; `withTimeout` and parent-scope cancellation propagate.

### Fixed

- Ollama provider error envelopes were silently passed through as `LlmResponse.Text(rawJson)`, causing user `transformOutput` to fail with a misleading "could not parse" error far from the provider boundary (#702).
- `Agent.mcp { }` could mutate the tool registry post-construction because `registerTool` didn't `checkNotFrozen()` — the "frozen after construction" invariant had a hole the reviewer flagged (#708).
- Agentic loop accepted repaired tool args without re-validating them through the typed schema (#658).
- `constructFromMap` accepted extra keys for plain data classes; sealed variants didn't verify the `type` discriminator matched (#665, #699).
- Tool name typos in `tools(...)` silently dropped instead of failing fast at construction (#631).
- Default budget was unbounded — agents could loop indefinitely without an explicit `maxTurns` (#633).

### Changed

- `model { ollama(...) }` Roadmap entry expanded — full budget set (`maxToolCalls`, `maxDuration`, `perToolTimeout`) plus the inline-tool fallback noted.
- README reorganized — new "What's in the Box" overview block with explicit "Implemented today / Experimental / Security model / Known limitations" subsections so users can distinguish today's APIs from aspirational ones (#643).
- PRD §5.6 documents the tool capability fallback as a portability principle.

### Docs

- README "What's in the Box" block — every implemented feature anchored to its detailed section + the issue # that established it.
- README + PRD: inline tool-call fallback documented with prompt-injection example.
- Wiki (out-of-tree) updates for MCP integration and Roadmap accuracy preceded this release.

### Internal / refactor

- Coroutine model rewritten — `runBlocking` only at the user-facing `invoke` shim; framework internals are suspend-native (#638).
- `Forum` and `Parallel` use `coroutineScope` for structured concurrency; cancellation propagates from parent scopes.
- `OllamaClient` made `open` with an `internal open fun sendChat` test seam — enables HTTP stubbing in unit tests without standing up a server.
- `OllamaClient.parseResponse` made `internal` for direct test access (matches the `buildRequestJson` pattern from #635).

### Tests

- 596 → 602 default-suite tests, all green.
- Live-LLM integration tests (`./gradlew integrationTest`):
  - `gemma3:4b + tools triggers inline fallback and tool gets executed` (single-tool case)
  - `gemma3:4b solves parenthesized arithmetic via evaluate tool` (string args)
  - `gemma3:4b computes 10th Fibonacci via fib tool` (integer args)
- 6 new `runTest`-based tests for cancellation and structured concurrency in the suspend layer.

### Migration notes

**No breaking changes.** Existing code keeps working unchanged:

```kotlin
// Old code — still works exactly as before:
val result = myAgent("input")
val list = (a / b)("input")
val out = (a then b)("input")
```

**Optional:** for callers in coroutine scopes, the new suspend entry points let you skip the blocking shim and propagate cancellation cleanly:

```kotlin
runBlocking {
    val result = myAgent.invokeSuspend("input")               // no nested runBlocking
    val list = (a / b).invokeSuspend("input")                 // structured concurrency
    val out = (a then b).invokeSuspend("input")
    val bounded = withTimeoutOrNull(2.seconds) {              // works now
        slowParallel.invokeSuspend("input")
    }
}
```

**No deprecations.** The blocking shims are documented as the back-compat surface, not deprecated — call whichever fits your context.

### Acknowledgements

Most of this release is driven by sustained external code-review feedback over several rounds. Thank you to the reviewers who pushed for typed tool args, the strict authorization model, the frozen-after-construction guarantee, and the suspend refactor. The "frozen after construction" claim now holds without the `mcp { }` caveat the latest review flagged.

[0.2.0]: https://github.com/Deep-CodeAI/Agents.KT/releases/tag/v0.2.0
