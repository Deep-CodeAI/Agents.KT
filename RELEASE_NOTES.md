# Agents.KT v0.5.0 — Streaming runtime + MCP-as-skills

**Release date:** 2026-05-16

v0.4.x established what an agent IS — typed boundaries, kotlin-reflect optional, KSP @Generable compile-time codegen. **v0.5.0 establishes what an agent does to the outside world**: it streams, and it speaks MCP fluently in both directions.

```kotlin
implementation("ai.deep-code:agents-kt:0.5.0")
implementation("ai.deep-code:agents-kt-ksp:0.5.0")  // optional but recommended
```

Drop-in for v0.4.6 consumers. Every existing API works unchanged. Streaming and MCP-as-skills are additive surfaces — opt in when you want them.

---

## What ships

### Streaming inside the agent loop

```kotlin
val session = myAgent.session(input)

session.events.collect { event ->
    when (event) {
        is AgentEvent.Token              -> render(event.text)
        is AgentEvent.ToolCallStarted    -> log("→ ${event.toolName}")
        is AgentEvent.ToolCallFinished   -> show(event.result, event.isError)
        is AgentEvent.SkillStarted       -> log("skill: ${event.skillName}")
        is AgentEvent.SkillCompleted     -> log("✓ ${event.tokensUsed} tokens")
        is AgentEvent.Completed          -> done(event.output)
        is AgentEvent.Failed             -> err(event.cause)
        else                             -> {}
    }
}

val output: OUT = session.await()
```

`agent.session(input): AgentSession<OUT>` is the consumer-facing entry point: a cold `Flow<AgentEvent<OUT>>` of typed events plus a `suspend fun await()` terminal. Eight event subtypes cover the whole lifecycle. Every event carries `agentId` so consumers can demultiplex composed streams.

**Native wire-level streaming on all three adapters:**

| Provider | Protocol | Live result (count 1→10 prompt) |
|---|---|---|
| Ollama | NDJSON (`stream: true`) | 19 chunks across the response |
| Anthropic | SSE with indexed content blocks | 2+ chunks, ~30ms span |
| OpenAI | SSE with `[DONE]` terminator | 19 chunks, ~200ms span |

Anthropic's streaming gets the harder case right: SSE can interleave `content_block_delta` events for text and `tool_use` blocks at different indices, so the adapter tracks `Map<Int, BlockState>` and routes each delta to the right block. The `toolu_*` id from `tool_use` blocks flows through verbatim as `LlmChunk.ToolCallStarted.callId` — that's what `ToolCall.callId` was designed for.

Cumulative `TokenUsage` flows on `SkillCompleted` and `Completed` — summed across every LLM turn of one skill invocation, prompt and completion tokens accumulated independently. Per-skill billing visibility without a separate listener.

### Every composition operator surfaces a session

Composition preserves provenance: events from each inner agent flow through with their own `agentId`, terminated by a single `Completed`/`Failed`. The framework's eight composition operators (counting Pipeline overloads as one) all expose sessions:

```kotlin
// Sequential: a runs to completion (streaming its tokens), then b starts.
val pipe = parse then generate then review
pipe.session(input).events.collect { ... }

// Conditional: source streams, then the matched route's agent streams.
val routed = classifier.branch<String, Decision, String> {
    on<Approved>() then approvedHandler
    on<Rejected>() then rejectedHandler
}
routed.session(input).events.collect { ... }

// Concurrent: events from both branches interleave, demultiplexable by agentId.
val parallel = analyzer / critic
parallel.session(input).events.collect { event ->
    when (event.agentId) { "analyzer" -> ..., "critic" -> ... }
}

// teacher wrap student: teacher streams, its output becomes student's prompt override, student streams.
val constrained = supervisor wrap worker
constrained.session(input).events.collect { ... }

// Loop / Forum / Swarm — same shape: inner events flow, single terminal.
```

For `Swarm.absorb(sibling)`, the sibling's inner events stream into the captain's session between the captain's own `ToolCallStarted` and `ToolCallFinished` brackets. `ToolDef` gained a `sessionExecutor` channel — any future sub-agent-wrapping tool can plug into it.

### MCP-as-skills unification

The point of v0.5.0's birth. **An MCP capability and an agent Skill are the same shape** — named, described, typed unit of work. v0.5.0 makes that literal: all three MCP capability surfaces expose as `Skill<Map<String, Any?>, String>`:

```kotlin
val mcp = McpClient.connect(url)
val agent = agent<Map<String, Any?>, String>("wrapper") {
    skills {
        mcp.toolSkills().forEach { +it }       // every callable function
        mcp.promptSkills().forEach { +it }     // every prompt template
        mcp.resourceSkills().forEach { +it }   // every URI-addressable doc
    }
}
```

That's the entire integration. The agent's skill-selection logic — manual `skillSelection { }` routing or automatic LLM routing — dispatches between MCP capabilities the same way it dispatches between native skills. No special case.

`McpClient` gains the new RPC layer underneath:
- `listPrompts()` + `getPrompt(name, args): String` (joins MCP message content blocks)
- `listResources()` + `readResource(uri): String`

`McpServer.from(agent)` gains the corresponding DSL — register prompt templates and static resources alongside the existing tool exposure:

```kotlin
McpServer.from(agent) {
    port = 0
    expose("respond")                                                // tool (existing)
    prompt("review_math", "System prompt for reviewers",
           arguments = listOf(McpPromptArgument("topic", required = true))) { args ->
        "You are reviewing math on ${args["topic"]}. Be precise."
    }
    resource("policy:///precision.md", "precision-policy",
             description = "Internal policy", mimeType = "text/markdown") {
        "Be precise. Cite sources. Round half-to-even."
    }
}
```

The server now declares `prompts` and `resources` capabilities in its `initialize` response when registrations exist, and handles `prompts/list`, `prompts/get`, `resources/list`, `resources/read` over the wire. `McpClient.snapshot: McpServerInfo` gives consumers a single immutable view of the connected server's full capability surface.

`mcp.toolDefs()` (tools-as-auxiliary-functions, the v0.4.x shape) stays. Consumers pick the shape that matches their agent design:
- `toolDefs()` → MCP caps as helpers an agent's skill calls during its agentic loop
- `toolSkills()` → MCP caps as primary entry points the agent dispatches between (use case: agent IS a thin wrapper over MCP)

### Self-contained MCP test infrastructure

Live-MCP tests no longer need `MCP_REDMINE_URL`. The framework hosts both ends of the wire — `McpServer.from(agent)` on a loopback port, `McpClient.connect(server.url)` on the other end. The new `LoopbackMcpAlgebraTest` exercises a real-math round-trip: agent computes `sqrt(π/e)` (digits-as-arrays + BigInteger), exposes via MCP, client reads it back, verifies with both a `Math.sqrt` floor and a BigDecimal square-back proving `result² ≈ π/e` to 20 decimal places.

Three pre-existing tests (`McpClientLiveTest` × 2 + `AgentMcpToolUseTest`) converted from `MCP_REDMINE_URL`-gated to loopback fixtures. `./gradlew mcpIntegrationTest` runs **7 tests, 0 skipped, 0 failures** out of the box.

### Test growth

| Suite | Tests | Failures |
|---|---|---|
| Unit (root + KSP + no-reflect smoke) | **1,074+** | 0 |
| Live-LLM integration | 54 | 0 (clean runs) |
| Live-MCP integration | 7 | 0 |

`./gradlew testAll` aggregates everything — the canonical pre-push command.

---

## Premortem-driven discipline

`docs/premortem-0.5.0-streaming.md` was written before any v0.5.0 code shipped. It listed:
- The typed `AgentEvent` hierarchy (now in code)
- The `AgentSession` shape (now in code)
- The cancellation contract (verified by per-adapter regression-guard tests)
- The composition fidelity matrix (every operator implemented per the matrix)
- Seven success criteria (all ticked)

Every claim in this release points at a premortem criterion. No floating wins.

`docs/streaming.md` is the consumer-facing reference: API walkthrough, provider streaming status with live-measured numbers, cancellation contract, test coverage map.

---

## Honest gaps deferred past v0.5.0

- **HTTP socket cancellation via `sendAsync`** — Kotlin Flow's channel-backed `emit` already propagates collector cancellation cleanly (verified by per-adapter regression-guard tests). The blocking `BufferedReader.readLine()` doesn't get interrupted mid-line — that's a latency optimization, not a correctness gap. `sendAsync` migration lands in a future patch.
- **Coroutine-aware per-tool timeouts** — depends on the `sendAsync` migration. Today's `Thread.join(timeout)` per-tool deadline still works for synchronous tools; suspending tools (the `sessionExecutor` path for absorbed siblings) bypass it.
- **Binary MCP resources** — `resourceSkills()` returns text content from the `text` field of `resources/read` responses. Base64 binary content isn't exposed yet.
- **Sealed-root `fromLlmOutput` dispatch without `kotlin-reflect`** — still returns null (the v0.4.6 honest gap remains).

These are tracked; none block real usage of the v0.5.0 surface.

---

## Migration

**For v0.4.6 consumers: drop-in.** Bump the artifact version, you're done. Every existing API works unchanged.

To opt into streaming:
```kotlin
myAgent.session(input).events.collect { event -> /* ... */ }
```

To consume MCP servers via the unified skills surface:
```kotlin
val mcp = McpClient.connect(url)
agent<...> {
    skills { mcp.toolSkills().forEach { +it } /* + promptSkills, resourceSkills */ }
}
```

To expose your agent's prompts and resources via MCP:
```kotlin
McpServer.from(yourAgent) {
    expose("skill-name")
    prompt(...) { args -> ... }
    resource(...) { ... }
}
```

---

## Comparison checklist against `docs/premortem-0.5.0-streaming.md`

- [x] `AgentEvent` sealed hierarchy defined, `Completed<OUT>` carries typed output
- [x] `agent.session(input)` returns `AgentSession<OUT>` with cold `events` and `await()`
- [x] `agent.invoke` / `agent.invokeSuspend` continue to work byte-for-byte; every existing test passes unchanged
- [x] All three current adapters (Ollama, Anthropic, OpenAI) implement `chatStream` natively
- [x] Loopback `agents-kt-streaming-test`-equivalent coverage via integration tests
- [x] Documentation: `docs/streaming.md` and `README.md` updated
- [x] No regressions in `./gradlew testAll`

Every box ticked. No premortem criterion is unfulfilled at release time.

---

*The next chapter (v0.6.0): provider breadth (Google, Mistral, OpenRouter, Bedrock), Spring Boot + Ktor starters, OpenTelemetry, AgentUnit testing framework. Per `docs/roadmap.md` Phase 2.*
