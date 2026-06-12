# Premortem — suspend-native agentic loop (#3874 / #638, post-1.0)

Design groundwork for the async loop. **Not scheduled before 1.0** — this document exists so the
decision is made once, the blast radius is mapped before anyone writes code, and the interim
workarounds are named. Written against the 0.7.25-SNAPSHOT line (post-#3866/#3868/#3869).

## What "synchronous loop" actually means today

The public boundary is already two-track: `agent(input)` is a one-line `runBlocking` shim around
`invokeSuspend(input)`, and the internals (`executeAgentic`, composition operators, sessions) are
suspend functions. The real blocking residue is narrower than the README phrase suggests:

1. **`ModelClient.chat(messages)` is a synchronous interface method.** Every non-streaming model
   call blocks its carrier thread for the full provider round-trip. (`chatStream` is already
   `suspend` returning `Flow<LlmChunk>`.)
2. **Blocking tool executors** run on a sacrificial worker thread for `perToolTimeout`
   enforcement; the session-aware suspend path (#1903) already cancels cooperatively.
3. **Blocking HTTP reads mid-stream** aren't coroutine-cancellable (documented in
   streaming.md known gaps).

So the work is **"suspend-native model calls + cancellation cleanliness"**, not a loop rewrite.

## Target shape

- `ModelClient` gains `suspend fun chatSuspend(messages): LlmResponse` with a default
  implementation bridging to `chat` via `runInterruptible(Dispatchers.IO)` — adapters migrate one
  by one; the JDK `HttpClient.sendAsync` path makes each adapter natively cancellable.
- The loop calls `chatSuspend`; `runBlocking` survives only in the public blocking shims
  (`invoke`, Java interop) — that is by design, not debt.
- Actor-model deployments fall out for free: a suspend-native agent is hostable on any
  dispatcher/actor framework; we do NOT ship our own actor runtime (MCP/A2A hosting already
  covers the service shape).

## Blast radius (audit checklist before starting)

| Surface | Risk | Note |
|---|---|---|
| 7 provider adapters | medium | `sendAsync` migration per adapter; verify per-provider cancellation semantics (Anthropic SSE clean; Ollama varies) |
| Composition sessions (#3866) | low | already structured-concurrency; re-run the full session suites |
| Snapshot/resume (#2488) | medium | interrupt signal crosses suspend points — re-verify identity preservation |
| Before-hooks (`onBefore*`), historyCompression (#3865) | low | sync lambdas stay sync; they run between suspensions |
| `firstOf` (#3869) | improves | cancel-losers becomes prompt for model calls — revisit the no-await tradeoff |
| `HumanGateRegistry` (#3868) | none | blocking by design (decisions arrive out of band) |
| Java interop | low | keep blocking shims; smoke-test from a thread without an event loop |

## Acceptance gates (from the ticket, kept)

1. Outer-scope cancellation reaches an in-flight HTTP request < 100ms.
2. No `runBlocking` frames in thread dumps during suspend-path execution.
3. Blocking Java API still works; no throughput regression (microbenchmark).
4. All #3866 composition-streaming tests pass unchanged.
5. 1000 concurrent sessions on 4 cores without pool exhaustion.

## Interim workarounds (why this can wait)

- Request/response deployments: unaffected — the blocking shim is the natural shape.
- Actor-ish deployments: host the agent as an MCP (#1923) or A2A (#3864) server; concurrency
  lives in the HTTP layer.
- Latency: `firstOf`/`speculative` (#3869) already race past slow calls (with the documented
  no-await caveat for blocking bodies — the first thing this refactor improves).

## Decision

Defer implementation to post-1.0 (unchanged). First implementation slice when scheduled:
`chatSuspend` default + one adapter (OpenAI — `sendAsync` is mechanical) behind the existing
test matrix, then per-adapter migration, then the thread-dump and stress gates.
