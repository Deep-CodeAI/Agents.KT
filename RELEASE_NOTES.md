# Agents.KT v0.6.4 — Trust patch

**Release date:** 2026-05-30

0.6.4 is a deliberate **trust patch** on top of 0.6.3. Boring on features, focused on closing real boundary gaps that an outside auditor flagged in the 0.6.3 review. The tagline:

> 0.6.4 makes Agents.KT more tolerant of real model behavior without weakening runtime boundaries.

The product identity is unchanged: **auditable Kotlin agent runtime for regulated JVM teams**.

```kotlin
implementation("ai.deep-code:agents-kt:0.6.4")
implementation("ai.deep-code:agents-kt-ksp:0.6.4")           // optional but recommended
implementation("ai.deep-code:agents-kt-manifest:0.6.4")      // permission manifests
implementation("ai.deep-code:agents-kt-observability:0.6.4") // JSONL audit + ObservabilityBridge
// optional bridges
implementation("ai.deep-code:agents-kt-otel:0.6.4")
implementation("ai.deep-code:agents-kt-langsmith:0.6.4")
implementation("ai.deep-code:agents-kt-langfuse:0.6.4")
```

Drop-in for 0.6.3. No API renames, no removed methods. The behavior changes are spelled out below; in every case the new default is the safe one.

---

## What ships in 0.6.4

### Snapshot path-traversal closed (#2753)

`FileSnapshotStore.save / load / delete` previously concatenated the raw session id into the filename:

```kotlin
val target = dir.resolve("$key.json")
```

If session ids derive from any external input (request header, JWT subject, user-supplied id), a value like `"../../../etc/poisoned"` would let the caller read or write outside `dir`. The class header even admitted as much: *"v1: keys are used as filenames; assumes filesystem-safe session ids."*

0.6.4 hashes the key to SHA-256 hex before forming the filename. The original session id is still inside the snapshot body (`sessionId` / `requestId` fields) for traceability — only its filesystem representation changes. Deterministic, so repeated saves with the same key overwrite atomically.

```kotlin
val store = FileSnapshotStore(snapshotsDir)
store.save("../../../etc/poisoned", snap("evil"))
// → snapshotsDir/<sha256-hex>.json — inside snapshotsDir, NOT outside
store.load("../../../etc/poisoned")?.sessionId
// → "evil" (original session id preserved in body)
```

No on-disk migration needed; 0.6.3 had not shipped `FileSnapshotStore` widely enough for production state to exist.

### Manifest-hash restore guard (#2754)

`SessionSnapshot.manifestHash` already existed but wasn't enforced on resume. That let a snapshot taken under one tool/permission set replay silently against an agent whose manifest had since changed — tools added, policies tightened, secrets rotated. For an audit-first runtime that was the wrong default.

0.6.4 fails closed: if `snapshot.manifestHash` is non-null and disagrees with the current agent's `manifestHash`, resume throws `SnapshotManifestMismatchException`. Callers who own the migration story can opt out via a flag.

```kotlin
try {
    executeAgentic(agent, skill, input, resumeFrom = snap)
} catch (e: SnapshotManifestMismatchException) {
    // e.expected, e.actual — both hashes preserved for forensics
    // legitimate migration path:
    executeAgentic(agent, skill, input, resumeFrom = snap, allowManifestMismatch = true)
}
```

`null` snapshot.manifestHash is allowed regardless — back-compat for any pre-0.6.4 snapshots that don't carry one.

### Namespaced memory restore (#2755)

`MemoryBank` was documented to support a **shared-workspace topology** — one bank, many agents — but `MemoryBank.restore(state)` cleared the entire backing store. Resuming session A wiped session B's slot. That was a destructive default for a topology the README actively recommends.

0.6.4 pivots snapshot/restore to the natural per-agent namespace:

```kotlin
val bank = MemoryBank()
bank.write("ActorsAgent", "a-state")
bank.write("MerchAgent", "m-state")

// snapshot/restore one agent — leaves the other alone
val mine = bank.snapshotForAgent("ActorsAgent")
bank.restoreForAgent("ActorsAgent", mine)
// MerchAgent slot untouched
```

The wipe-all `restore(Map<String, String>)` is kept (Snapshotable interface contract) but deprecated. AgenticLoop's snapshot capture and resume both flow through the new namespaced methods.

### Tool-result JSON escaping fixed (#2756)

`wrapUntrustedToolResult` had a hand-rolled 5-char escape chain (`\\`, `"`, `\n`, `\r`, `\t`) that left the rest of U+0000–U+001F unescaped, producing invalid JSON for binary tool results, OCR text, captured terminal output. The central `toJsonString()` escaper (added in #2378) was the project-wide source of truth everywhere else; the local copy was the last holdout.

0.6.4 routes through `toJsonString()`. NUL / BS / FF / ESC and the rest of the control range are now escaped per RFC 8259 §7. Tool name is escaped too — a name containing `"` no longer breaks the envelope.

This is an adversarial-boundary fix; `untrustedOutput = true` tools exist precisely because their output might try to corrupt downstream parsing.

### `PipelineEvent.ToolHallucinated` audit event (#2757)

Since #2476, unknown / unlisted tool calls are recoverable — the runtime appends a tool-result error and continues. Good runtime behavior, but an auditor reviewing the JSONL audit stream could only distinguish "model hallucinated a tool" from "tool ran and returned an error" by parsing the error message body. Fragile.

0.6.4 surfaces hallucinations as a typed first-class event:

```kotlin
val a = agent<...> { ... }
a.onToolHallucinated { name, args, allowedTools ->
    auditLog.write("hallucination: $name, allowed=$allowedTools")
}

// Or via observe():
a.observe { event ->
    when (event) {
        is PipelineEvent.ToolHallucinated -> { /* grep by event class */ }
        is PipelineEvent.ToolDenied -> { /* different reason */ }
        is PipelineEvent.ToolCalled -> { /* executed normally */ }
        else -> { }
    }
}
```

Streaming consumers still get `ToolCallFinished(isError = true)` on the same wall-clock — `ToolHallucinated` is additive evidence, not a replacement.

### `onBudgetExceeded` broadened (#2750)

#2412 wired the `onBudgetExceeded` handler for `TOOL_CALLS` only. The other reasons — `TURNS`, `DURATION`, `TOKENS`, `CONSECUTIVE_TOOL` — threw unconditionally even when a handler was registered. The handler contract was asymmetric.

0.6.4 fires the handler at every cumulative throw site with the same `Stop` / `Extend(newLimit)` semantics. `Extend` raises the local limit and the loop continues; `Stop` or a missing handler throws exactly as before.

```kotlin
agent<String, String>("a") {
    budget {
        maxTurns = 8
        maxToolCalls = 32
        maxDuration = 30.seconds
        maxTokens = 100_000
        maxConsecutiveSameTool = 3
    }
    onBudgetExceeded { reason, current ->
        when (reason) {
            BudgetReason.TURNS            -> BudgetDecision.Extend(current + 4)
            BudgetReason.TOOL_CALLS       -> BudgetDecision.Extend(current + 16)
            BudgetReason.TOKENS           -> BudgetDecision.Extend(current * 2)
            BudgetReason.DURATION         -> BudgetDecision.Extend(60_000) // millis
            BudgetReason.CONSECUTIVE_TOOL -> BudgetDecision.Stop
            BudgetReason.PER_TOOL_TIMEOUT -> BudgetDecision.Stop // still always throws
        }
    }
}
```

Units when `Extend(newLimit)` is returned:

| Reason | Unit |
|---|---|
| TOOL_CALLS / TURNS / TOKENS / CONSECUTIVE_TOOL | integer count |
| DURATION | milliseconds |

PER_TOOL_TIMEOUT stays unconditionally throwing — extending a single in-flight tool mid-execution needs interrupt semantics and belongs in a separate ticket.

### Docs and release hygiene

The auditor's biggest 0.6.3 concern was docs/code drift. 0.6.4 reconciles:

- README dependency coordinate `0.6.0` → `0.6.4`, lead paragraph rewritten for the full 0.6 line, "Current Release" blurb chronologically restructured.
- `RELEASE_NOTES.md` (this file) refreshed from the stale v0.5.0 body.
- Provider count consistent everywhere: four built-in adapters — Ollama, Anthropic, OpenAI, DeepSeek (`docs/model-and-tools.md`, `SECURITY.md`).
- Unknown-tool behavior described correctly: recoverable error, not `IllegalStateException` (`docs/prd.md`, `docs/model-and-tools.md`).
- MCP server adjunct fixed: output routes through `toLlmInput` (per #2483), not raw `toString()`.
- CHANGELOG duplicate `## [0.6.3]` header removed.
- CHANGELOG [0.6.2] attribution entry annotated with the 0.6.3 revert (the API no longer exists).

---

## Compatibility

| Change | Affects | Migration |
|---|---|---|
| `FileSnapshotStore` hashes filenames | On-disk snapshot files written by 0.6.3 (unlikely in production) | If any exist, re-save via 0.6.4 |
| Manifest-hash restore guard | Resume across an agent rebuild that changed tools/policies | Catch `SnapshotManifestMismatchException` or pass `allowManifestMismatch = true` |
| `MemoryBank.restore(Map)` deprecated | Callers calling `bank.restore(...)` directly | Switch to `restoreForAgent(agentName, value)` — internal callers already updated |
| `wrapUntrustedToolResult` escaping | Consumers that parse the JSON envelope of an `untrustedOutput = true` tool | Parsers that were tolerant of invalid JSON keep working; valid JSON consumers see the fix |
| `onBudgetExceeded` broadened | Existing handlers receive calls for new reasons | Add a `when` branch per reason or default to `BudgetDecision.Stop` |

Source-compatible drop-in for 0.6.3 in every other respect.

---

## Verification

```bash
./gradlew test    # full suite across all modules
```

Manifest review (audit-time):

```bash
./gradlew :agents-kt-manifest:permissionManifestVerify
```

---

## Where to read more

- [`README.md`](README.md) — feature index pointing at `docs/`.
- [`docs/permission-manifest.md`](docs/permission-manifest.md) — manifest semantics and the SHA-256 hash that feeds the restore guard.
- [`docs/threat-model.md`](docs/threat-model.md) — what 0.6 owns vs. what your deployment owns.
- [`docs/regulated-deployment.md`](docs/regulated-deployment.md) — wiring for compliance-supporting evidence.

---

## Credits

The 0.6.4 trust patch was scoped from an outside-auditor review of 0.6.3 (#2752 epic). Sub-tickets: #2753 (FileSnapshotStore hashing), #2754 (manifest-hash restore guard), #2755 (namespaced memory restore), #2756 (untrusted-tool JSON escaping), #2757 (PipelineEvent.ToolHallucinated), #2750 (onBudgetExceeded broadening).
