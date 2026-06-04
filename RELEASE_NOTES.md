# Agents.KT v0.7.23 — Maintainability + a model-error policy

**Release date:** 2026-06-04

Closes the bulk of the code-smell remediation epic (#2790), finishes the `AgenticLoop`
decomposition begun in 0.7.21, and makes the model-error contract explicit with a new `onLLMError`
recovery hook. The maintainability changes are behavior-preserving (no public-API change);
`onLLMError` is the one additive public API. Drop-in on the 0.7.x line.

```kotlin
implementation("ai.deep-code:agents-kt:0.7.23")
implementation("ai.deep-code:agents-kt-ksp:0.7.23")
```

## Added — `onLLMError` model-failure policy (#3508)

Prompted by a *funny case*: Ollama down, but an agent kept running on its hardcoded logic — because
the resolved skill was a non-agentic `implementedBy`, so the model was never contacted (by design).
The contract is now explicit:

- **Model configured** → a failed model call in the agentic loop (a down server — surfacing as the
  raw transport error, e.g. `ConnectException` — a 5xx, or a malformed response) **fails fast and loud
  by default**.
- **No model** → `implementedBy` skills run deterministically; no model error can arise.
- **`agent.onLLMError { e -> LlmErrorDecision }`** opts into recovery: `RespondWith(fallback)` uses a
  canned/typed value (routed through the agent's `castOut`) instead of throwing; `Rethrow` (the
  default with no handler) keeps it loud. The handler receives the **original** exception — identity
  is preserved. Does not fire for budget caps (`onBudgetExceeded`) or cancellation. Recovery is scoped
  to the agentic loop in this release; a model failure during multi-skill LLM routing still propagates
  loud (follow-up).

## Changed — god-class / god-file decomposition (behavior-preserving)

- **`Agent` → `InterceptorChain` + `ListenerRegistry` (#2793).** The before-interceptor subsystem and
  the observability listener slots move into their own collaborators; three copy-pasted
  swallow-and-log blocks collapse into one `dispatchSafely`, and `decideBeforeToolCall` reuses the
  shared `runDecisionChain`. `Agent` keeps its public `onX` DSL and `agent.<slot>` reads.
- **`McpServer` → HTTP intake + `McpDispatcher` (#2795).** The transport-agnostic JSON-RPC core
  extracts into `McpDispatcher`; `handle()` slims via new `validateRequest` / `readBoundedBody`;
  `McpStdioServer` drives the dispatcher directly, removing the `internal dispatchJsonRpc` back door.
  `McpServer.kt` 451 → 215.
- **`LiveShow` → `LiveShowBanner` + `SpinnerAnimation` (#2798).** The banner asset and the in-place
  spinner split out; the spinner becomes an `AutoCloseable` (`spinner.use { … }`), removing a manual
  `Thread` and an `AtomicBoolean` that *shadowed* the `LiveShow.running` field.

## Changed — duplication removed

- **One streaming-session scaffold (#2797).** The five composition operators (`branch` / `forum` /
  `loop` / `parallel` / `pipeline`) shared an identical ~25-line `session(input)` scaffold; one
  `agentSessionScope(terminalAgentId, body)` now owns the channel / scope / runtime-context /
  terminal-event lifecycle. Net **−282 lines**.
- **One deliberation/match core for `Forum` and `Branch` (#2802).** `Branch.invokeSuspend` delegates
  to `matchRoute`; `Forum`'s deliberation extracts into one `deliberate(input, runAgent)` shared by
  the streaming and non-streaming paths.

## Changed — typed seam over weak boundaries

- **`GenerableCodec` (#2803).** `@Generable` deserialization resolves through one `KClass<T>.codec()`
  boundary (KSP-generated when present, else reflective); the MCP edge reuses it. The unchecked casts
  sprinkled across `constructFromMap` / `coerceValue` / `fromLlmOutput` and the MCP request path
  collapse to one site each.
- **AgenticLoop completion (#3423, building on #3376 / #3406).** The last setup block in
  `executeAgentic` — the per-skill tool-set assembly + authorization allowlist — extracts as
  `resolveAllowedTools`. The remaining loop body is the turn loop itself.

## Quality

The detekt-baseline ratchet fell **423 → 415** and the main-module `@Suppress("UNCHECKED_CAST")`
count **42 → 30**. Each change is covered by the existing test suites (behavior-preserving) plus
focused new unit tests at every extracted seam, and a new `LlmErrorPolicyTest` for the error policy.

## Compatibility

Drop-in on the 0.7.x line. The only new public API is the additive `onLLMError` hook (default
behavior is unchanged — model errors already failed loud). No behavior changes to existing surfaces,
no deprecations. The only open child of the maintainability epic (#2790) is #2791 — the turn-loop
core of `executeAgentic` — deliberately deferred as the highest-risk refactor.
