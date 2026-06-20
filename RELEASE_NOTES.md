# Agents.KT v0.8.1 — The agentic web: discover, serve, and get paid

**Release date:** 2026-06-20

0.8.0 made agents **talk to each other** (A2A) and **see and hear** (multimodal). 0.8.1 completes the
agentic-web quartet: an agents.kt agent can now be **discovered** (AGNTCY), **served to a frontend**
(AG-UI), **queried as web content** (NLWeb), and **paid** — both as a seller and, experimentally, as an
autonomous buyer (x402). Additive throughout: existing public API is preserved (drop-in on 0.8.0).

## Headlines

- **AGNTCY interop (#4518–#4521, epic #4517).** Export an agent as an **OASF 1.0.0** discovery record
  (`agent.toOasfRecord(...)`) and import + fail-closed-validate it back (`fromOasfRecord()`); publish and
  discover records in the content-addressed **DIR** directory (`:agents-kt-dir`, gRPC Store/Search/Routing);
  and verify an AGNTCY **Identity** badge (a JOSE/JWS Verifiable Credential) against an issuer's JWKS
  (`:agents-kt-identity`, fail-closed — rejects `alg:none`, `HS*` confusion, expiry, tamper).
- **AG-UI — serve an agent to a frontend (#4523, PRD §12.7).** `AgUiServer.from(agent)`: a `RunAgentInput`
  `POST` returns an **SSE stream of typed AG-UI events**, bridged live from the agent's `AgentSession` — for
  a CopilotKit-style chat. Now streams live **REASONING** (#4629; `THINKING_*` deprecated) and emits
  **`TOOL_CALL_RESULT`** (the executor return — #4680), so a UI renders tool outputs, not just invocations.
- **NLWeb — agent ↔ web content (#4541/#4542, PRD §12.9).** `NlWebServer.from(agent)` serves the NLWeb
  `POST /ask` contract; the `nlwebSearch` tool queries any NLWeb endpoint and folds ranked schema.org results
  into context as untrusted data.
- **x402 — agent payments (epic #4526, PRD §12.8), experimental.** *Seller* (#4527): `X402PaymentGate`
  gates any served endpoint behind a settled USDC payment — you hold no key, take no custody, fail closed.
  *Buyer* (#4528): `X402Client` drives `request → 402 → pay → retry`, signing a real EIP-712/EIP-3009
  authorization (secp256k1 + Keccak-256, pinned byte-for-byte to ethers.js vectors). The buyer is
  **guardrails-first** — the key lives in `X402Account` below the model layer, and every payment clears an
  `X402SpendPolicy` (per-payment cap, payee/network allowlists, optional human-in-the-loop) before any
  signature.
- **Audit-ledger records cross-cutting misbehaviour (#2905).** The one tamper-evident Merkle chain now folds
  in budget-exceeded, hallucinated-tool, and policy signals that never flow through a tool body.
- **Default transient-network retry across all HTTP providers (#4560).** Idempotent model calls retry
  transient failures with backoff by default.

Plus a feasibility spike: **Agent → WebAssembly (#4548).** A typed agent compiles to `wasmJs` and runs in a
browser/node over `fetch` — verified against a local LLM. Verdict: **conditional GO** for a `wasmJs` profile;
docs only, no API change. See [`docs/wasm-feasibility.md`](docs/wasm-feasibility.md).

See [`CHANGELOG.md`](CHANGELOG.md) for the full, itemized list, and the new guides
[`docs/agui.md`](docs/agui.md), [`docs/x402.md`](docs/x402.md), and [`docs/wasm.md`](docs/wasm.md).

## On the experimental surfaces

The **x402 buyer** moves irreversible money. It is gated behind hard guardrails (key below the model layer,
mandatory spend policy, fail-closed denial) and is marked experimental — test it on **Base Sepolia** (free
testnet facilitator) before pointing it at mainnet. Still deferred: scoped ERC-4337 session keys, the `upto`
metered scheme, Solana, and cross-payment velocity limits.

## Deferred to 0.9.0

The Layer-2 **sandbox backends** still want a Linux-capable build/verify environment: `DockerSandbox` (#2895),
the egress hostname-allowlist **proxy** (#2893), and **read confinement** (#4546). `WasmSandbox` (#2894)
remains closed won't-do; the agent → WASM export track (#4547) continues from this release's spike.

## Compatibility

Additive, no breaking changes to existing public API. AGNTCY / AG-UI / NLWeb / x402 surfaces are all opt-in;
the x402 buyer pulls BouncyCastle (`bcprov`) as a runtime dependency for signing. CodeQL's `java-kotlin` check
is red on the Kotlin 2.4 toolchain (upstream codeql#21938) — the Gradle build is the gate.
