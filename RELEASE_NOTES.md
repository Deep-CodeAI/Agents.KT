# Agents.KT v0.6.6 — Maintainability + cancellation hotfix

**Release date:** 2026-05-30

0.6.6 is a focused maintainability pass on top of 0.6.5: a 10-ticket code-smell remediation epic (#2790) plus a field-reported correctness fix for session cancellation (#2863). The tagline:

> 0.6.6 keeps Agents.KT's surface stable while shrinking what a future audit can flag.

The product identity is unchanged: **auditable Kotlin agent runtime for regulated JVM teams**.

```kotlin
implementation("ai.deep-code:agents-kt:0.6.6")
implementation("ai.deep-code:agents-kt-ksp:0.6.6")           // optional but recommended
implementation("ai.deep-code:agents-kt-manifest:0.6.6")      // permission manifests
implementation("ai.deep-code:agents-kt-observability:0.6.6") // JSONL audit + ObservabilityBridge
// optional bridges
implementation("ai.deep-code:agents-kt-otel:0.6.6")
implementation("ai.deep-code:agents-kt-langsmith:0.6.6")
implementation("ai.deep-code:agents-kt-langfuse:0.6.6")
```

Drop-in for 0.6.5. No API renames, no removed methods. The cancellation fix is the only behavior change and it's strictly more correct (cancellation propagates instead of being swallowed as a synthetic `Failed` event).

---

## What ships in 0.6.6

### Fixed — Session catch swallowed CancellationException as AgentEvent.Failed (#2863)

A downstream production agent on `claude-opus-4-7` cancelled an in-flight session (user closed the browser tab; SSE bridge cancelled the flow subscription). The session-extension's outer `catch (t: Throwable)` block treated the `CancellationException` identically to a real failure: it emitted a synthetic `AgentEvent.Failed`, closed the channel cleanly, and swallowed the cancel from the surrounding scope. Downstream rendered the cancellation to the user as "FlowSubscription was cancelled", clobbering already-streamed partial output.

0.6.6 rewrites the catch as ordered multi-catch:

```kotlin
} catch (timeout: TimeoutCancellationException) {
    // budget / withTimeout breach — real failure, emit Failed
    channel.send(AgentEvent.Failed(agentId, timeout))
    channel.close()
    result.completeExceptionally(timeout)
} catch (cancel: CancellationException) {
    // bare cancellation — propagate per structured concurrency
    result.completeExceptionally(cancel)
    channel.close(cancel)
    throw cancel
} catch (t: Throwable) {
    // every other failure — emit Failed
    ...
}
```

Order matters: `TimeoutCancellationException` extends `CancellationException`, so the timeout case must come first or bare-cancel would swallow it. `TimeoutCancellationException` stays on the `Failed` path — a budget or `withTimeout` breach is a genuine error consumers must hear about in audit logs and downstream SSE.

Applied to all six session extensions (`AgentSessionExtension`, `PipelineSessionExtension`, `ParallelSessionExtension`, `BranchSessionExtension`, `LoopSessionExtension`, `ForumSessionExtension`). Pinned by new `SessionCancellationTest` with two structural cases plus four per-vendor cases (Ollama / Claude / OpenAI / DeepSeek) using stub `ModelClient` injections — a future regression specific to one adapter's session integration can't slip past CI.

### Changed — Maintainability epic #2790 (10 refactors)

A code-smell audit landed ten focused, behavior-preserving refactors. No public API removals. Highlights:

- **#2806** — Centralised `Ansi` constants in `LiveShow`; suspending `send` on bracket session events with JUL warnings on `trySend` failure; `BuildInfo.version` from JAR manifest replaces three drifted MCP version constants.
- **#2805** — `ToolRisk` consolidated; `Agent.describeBudget()` reflection removed (restores reflect-optional contract #1718); broad catches FINE-logged via `tryGenerable`; `ManifestYaml` indent magic numbers named.
- **#2804** — Reused `RESERVED_MEMORY_TOOL_NAMES`; named hash-prefix and Anthropic-cap constants; `MutableList<ToolDef>.reserveName(name)` collapses 5× duplicated require; `Severity.valueOf` bad parse logs at WARNING; `emitToolFinished()` helper replaces 4 near-identical `AgentEvent.ToolCallFinished` emit blocks.
- **#2799** — `JsonEscape` moved to `agents_engine.internal` so generation + core depend without inverting direction; `ToolPolicy.ManifestJson.quote`, `Snapshot`, `GenerableSupport.escapeJson` route through `toJsonString()`; `OPEN_EMPTY_OBJECT_SCHEMA_JSON` promotes the repeated empty-schema literal; `ClaudeClient` cache-control surgery extracted; control-char regression test in `UntrustedToolOutputTest`.
- **#2796** — Shared `agents_engine.mcp.JsonRpc` helper for envelope encoding + parsing; `JsonRpcWire` owns the `"2.0"` literal and wire keys; `JsonRpcErrorCode` names the standard error codes; new `McpException` hierarchy (`Transport / Protocol / ToolFailure`) extending `IllegalStateException` for back-compat.
- **#2792** — `HttpModelClientSupport.sendBounded(...)` consolidates the duplicated bounded-read + OOM-guard pattern across Claude / OpenAI / Ollama. `ModelClient.chatStream(messages)` (one-arg) delegates to the two-arg form instead of carrying a byte-identical clone.
- **#2800** — Four file-private helpers (`resultArray`, `joinTextContent`, `prefixed`, `makeMcpSkill`) collapse MCP client's parallel list-parsing and Skills-factory boilerplate; `toolSkills` / `promptSkills` / `resourceSkills` 8 lines each → 3-4.
- **#2794** — `toLlmInput` + `jsonSerialize` collapsed via parameterised `serializeForLlm(value, quoteTopLevelStrings)` walker.
- **#2801** — Primary `(String) -> Any?` overload on `LiveShow.from` + `LiveRunner.serve`; future operator types pass a method reference instead of forcing a new typed overload.
- **#2807** — detekt static analysis wired into the build with a baseline freezing existing violations and a `detekt.yml` tuned for the categories the audit found (complexity, MagicNumber, SwallowedException, UnusedPrivateMember). `./gradlew detekt` runs alongside `./gradlew test`.

### Notes

- No public API removals. `agents_engine.model.JsonEscape` → `agents_engine.internal.JsonEscape` is `internal`-only, so it's a binary-compatible relocation for any consumer using only the public API.
- The detekt baseline (`detekt-baseline.xml`, 788 lines) is checked in; future PRs are held to the rules without retroactively forcing cleanup of the audited code.

---

## Verification

```bash
./gradlew test    # full suite across all modules
./gradlew detekt  # static analysis against the baseline
```

Manifest review (audit-time):

```bash
./gradlew :agents-kt-manifest:permissionManifestVerify
```

---

## Where to read more

- [`README.md`](README.md) — feature index pointing at `docs/`.
- [`docs/multimodal.md`](docs/multimodal.md) — `Content` + vision + attachments + documents reference (unchanged from 0.6.5).
- [`docs/model-and-tools.md`](docs/model-and-tools.md) — `model { }` DSL including `requestTimeout` / `connectTimeout` (from 0.6.5).
- [`docs/eval.md`](docs/eval.md) — eval harness reference (unchanged from 0.6.5).
- [`docs/permission-manifest.md`](docs/permission-manifest.md) — manifest semantics and the SHA-256 hash that feeds the restore guard.

---

## Credits

Field report driving the cancellation hotfix from a downstream production agent. Maintainability audit (#2790) from manual code review; remediation by the framework team across ten focused PRs.
