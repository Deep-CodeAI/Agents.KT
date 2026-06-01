# Agents.KT v0.7.2 — Tool-security hardening

**Release date:** 2026-06-01

The self-contained first phase of the **capability-ABI epic (#2882)** — every change additive and back-compat. Drop-in for 0.7.x.

```kotlin
implementation("ai.deep-code:agents-kt:0.7.2")
implementation("ai.deep-code:agents-kt-ksp:0.7.2")
```

## Added

### Tamper-evident audit ledger (#2886)
`ToolAuditLedger` (in `agents-kt-observability`) — an **append-only, Merkle-chained, PII-safe** record of every tool action. Each row's hash chains to the previous, so `ToolAuditLedger.verify(path)` recomputes the chain and pinpoints the first **edited / inserted / deleted / reordered** row. The tool result is stored only as a hash, never raw. Auto-wire with `agent.events.ledger(file)` (records `ToolCalled`→`APPROVED`, `ToolDenied`→`DENIED`, `ToolHallucinated`→`HALLUCINATED`). *(Per-tool-call callId-keying of denied/hallucinated rows is a tracked follow-up.)*

### Argument-size cap (#2888)
`budget { maxToolArgsBytes = … }` (`Long?`, default `null` = off) hard-caps a single tool call's argument byte size, checked **before** the executor runs — so an oversized (often prompt-injected) call is rejected, not executed. Unconditional like `perToolTimeout`; surfaces as `BudgetExceededException(reason = BudgetReason.TOOL_ARGS_SIZE)`.

### `agents-kt-detekt` rules module (#2885, #2884)
A new module shipping custom detekt rules for tool executor bodies:
- **`ToolBodyForbiddenApis`** — flags raw `java.io.File` / `java.net.URL` / `ProcessBuilder` / `Runtime.exec` / `Class.forName` / `Unsafe` used **inside a tool `executor { }` body** (suppressible with `@Suppress` + a reviewed reason). Dogfooded on the framework's own source.
- **`ToolCapabilityExtractor`** — statically classifies what an executor body does (`FS_READ`/`FS_WRITE`/`NETWORK`/`ENVIRONMENT`/`EXEC`); the input the upcoming `ToolPolicy`↔capability comparator checks against the declared policy.

Consumers opt in via `detektPlugins("ai.deep-code:agents-kt-detekt")`.

### Release guard (#2873)
`checkReadmeVersion` (wired into `check`) fails the build if the README's `ai.deep-code:agents-kt:<version>` snippet drifts from the Gradle version — the exact drift an external 0.7.0 review flagged.

## Deferred (next, larger slice)
The `ToolEnvironment` ABI + the `executor { args -> }` → `{ args, env -> }` migration (#2883/#2889) and the `ToolPolicy`↔capability comparator (#2887) — the architectural core of #2882. Plus the Wasm/Docker/proxy/grants sandbox backends earmarked for 0.8.

## Compatibility
Drop-in for 0.7.x. New API is additive and opt-in (a tool that declares nothing is unaffected; `maxToolArgsBytes` defaults to off; the detekt rules are opt-in via `detektPlugins`).
