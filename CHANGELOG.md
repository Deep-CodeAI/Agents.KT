# Changelog

All notable changes to Agents.KT are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Pre-1.0, minor bumps may add new public API; existing API surface is preserved.

## [Unreleased]

### Added
- **Anthropic Claude adapter** — new `ClaudeClient: ModelClient` and `model { claude("claude-opus-4-7"); apiKey = "..." }` DSL. Maps the framework's `LlmMessage` / `LlmResponse` model to Anthropic's structured Messages API content blocks (`tool_use` / `tool_result`); tools advertise as `input_schema` (Anthropic's spelling); top-level `error` envelopes surface as `LlmProviderException`, same boundary contract as `OllamaClient` (#702). Provider dispatch in `AgenticLoop` constructs the client lazily so the agent's full tool catalog flows in. Live integration tests against the real API gated on a gitignored `.secrets/anthropic-key` (with `ANTHROPIC_API_KEY` env fallback); skipped via JUnit `Assumptions` when the key is absent (#1644).
- **`LiveRunner` precheck hook** — `LiveShowBuilder.precheck: (() -> Unit)?` runs after arg-parse and before banner / `--once` / REPL. Throw to abort startup; the runner prints `error: <msg>` and returns exit code 2. New `OllamaPreflight(host, port)` helper performs a `GET /api/tags` reachability check; wired into the swarm-demo captain so a misconfigured endpoint fails fast at startup instead of mid-spinner on the first turn (#1132).

### Changed
- `ModelProvider` enum gained `ANTHROPIC`. `ModelConfig` carries optional `apiKey`, `anthropicBaseUrl`, and `maxTokens` fields used by the Claude adapter. Default Ollama path is unchanged.

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
