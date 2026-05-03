# Changelog

All notable changes to Agents.KT are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Pre-1.0, minor bumps may add new public API; existing API surface is preserved.

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
