---
description: Source-file knowledge for agents_engine/x402/* — seller-side x402 agent payments (#4527, epic #4526, PRD §12.8). X402PaymentGate wraps any JDK HttpHandler so a resource is served only after a valid, settled stablecoin payment — fronts the agentic-web serve surfaces so an agent can monetize itself. Seller holds NO key and takes NO custody: the buyer signs EIP-3009, a hosted FacilitatorClient verifies+settles. Fails closed (402) on any failure. EXPERIMENTAL, seller-side only; buyer-side autonomous payment deliberately excluded. Call when the IDE LLM reasons about charging for an agent endpoint / x402.
---

# `agents_engine/x402/` — seller-side x402 payments (#4527)

[x402](https://github.com/x402-foundation/x402) revives HTTP `402 Payment Required` for gasless stablecoin
(USDC) payments. This is the **seller (safe) half**: let an agent **monetize itself** by gating a served
endpoint behind payment. Unlike MCP/A2A/AGNTCY/AG-UI (no money), x402 is a settlement layer that sits
*beneath* them.

```kotlin
val gate = X402PaymentGate(
    PaymentRequirements(network = "base", maxAmountRequired = "10000", payTo = "0xSeller", asset = "0xUSDC", resource = "/premium"),
    facilitator = HttpFacilitatorClient("https://facilitator.example"),
)
// front any JDK HttpHandler:
httpServer.createContext("/premium", gate.gate(downstreamHandler))
// ...or pass it straight to a serve surface (#4557):
NlWebServer.from(agent, payment = gate).start()   // also AgUiServer.from / A2AServer.from
```

## Why this is the *safe* half (the non-negotiables)

- **Seller holds no key, takes no custody.** The buyer signs an EIP-3009 `transferWithAuthorization`; a
  **hosted** [FacilitatorClient] verifies the signature and submits it on-chain. We only configure a public
  `payTo` address. A self-hosted *custodial* settler would trip money-transmitter regulation — hence the
  facilitator is an injected seam, hosted in production.
- **The LLM never touches money.** Gating is at the HTTP layer, entirely outside the agent loop — no key, no
  spend limit in any prompt. (Buyer-side autonomous payment, where that risk lives, is deliberately NOT built.)
- **Fails closed.** Missing/invalid payment, settle failure, or an unreachable facilitator → `402`; the
  resource is never served unpaid ([X402Exception] is caught and denied).

## Request flow (in `X402PaymentGate.gate`)

1. No `X-PAYMENT` header → `402` `{x402Version, error, accepts:[requirements]}` (the buyer learns the terms).
2. `X-PAYMENT` present → `facilitator.verify`; if valid and `settle`, `facilitator.settle`; on success set
   `X-PAYMENT-RESPONSE` (base64 settlement) **before** invoking the downstream handler (settle-before-serve,
   so the header is set before the response is written), then serve.

## Files

- `X402PaymentGate.kt` — the gate (HttpHandler wrapper).
- `PaymentRequirements.kt` — the seller's terms (`scheme`/`network`/`maxAmountRequired`/`payTo`/`asset`/…).
- `FacilitatorClient.kt` — the verify/settle seam (interface) + `HttpFacilitatorClient` (hosted REST).
- `FacilitatorVerification.kt` / `FacilitatorSettlement.kt` — results. `X402Exception` — fail-closed signal.

## Scope / follow-ups (epic #4526)

Seller-side gate, wired into `NlWebServer`/`AgUiServer`/`A2AServer` via `from(agent, payment = gate)` (#4557 —
they wrap the invocation handler `payment?.gate(h) ?: h`; A2A's agent-card discovery stays free). NOT here:
buyer-side autonomous payment (#4528 — scoped ERC-4337 session keys, signing below the model layer, HITL); a
granular MCP `paidTool()` wrapper (McpServer keeps per-tool pricing rather than a blanket gate); the official
`a2a-x402` extension. Facilitator field names follow the x402 facilitator REST spec; verify against a live
facilitator before production.
