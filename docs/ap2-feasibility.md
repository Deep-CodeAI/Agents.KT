# AP2 — Agent Payments Protocol feasibility spike (epic proposal, PRD §12.10)

[← Back to README](../README.md)

> **Status: feasibility spike — verify-first, experimental.** This proves the *assembly* (the PRD §12.10
> "1 + 1 = 3") with a working, tested `:agents-kt-ap2` module — not a production AP2 stack. Mandate **issuance**
> and non-x402 rails are out of scope.

[AP2](https://github.com/google-agentic-commerce/AP2) (Google, Sept 2025) is the **authorization** layer for
agent commerce: signed **mandates** prove *the human authorized the agent to buy*, while a settlement rail
(here, x402) moves the money. AP2 is an **A2A extension**, so it composes with what we already ship.

## TL;DR — GO (assembly proven end-to-end)

A typed Intent + Cart **mandate** (signed Verifiable Credentials) is **verified by the shipped
`IdentityVerifier`**, the authorization chain is enforced, and a verified cart **settles over a real
`X402PaymentGate`** — all in one hermetic test run (14 tests, no chain, no real money). AP2 for us is
**composition of three shipped pieces**, not a from-scratch protocol:

```
Intent/Cart Mandate (JWS VC)         A2A AgentCard                 x402
        │                                  │                         │
        ▼                                  ▼                         ▼
  :agents-kt-identity  ──►  MandateVerifier  ──►  Ap2Extension   Ap2Buyer ──► X402Client
  (verify, fail-closed)     (typed model +        (advertise on   (intent ⇒ X402SpendPolicy,
                             authz chain)          capabilities.    then settle the cart)
                                                   extensions)
```

## What the spike proves (`:agents-kt-ap2`, 14 tests)

1. **Mandates are the identity module's VCs.** `MandateVerifier` calls `IdentityVerifier.verify(jws, jwks)` and
   reads the typed `IntentMandate` / `CartMandate` out of the VC `credentialSubject`. The whole
   forgery-resistance surface comes **for free** — expired, tampered, `alg:none`, `HS*`-confusion, and
   wrong/unknown-key mandates are all rejected (tested), because a mandate *is* the JWS VC that module verifies.
2. **The authorization chain holds.** A Cart Mandate is payable only if its Intent Mandate authorized *this*
   spend: same intent id, within the cap, allowed merchant, matching asset/network. Each rejection path is
   tested.
3. **Intent Mandate ⇒ `X402SpendPolicy` (the PRD thesis, concrete).** `Ap2Buyer.spendPolicyFrom(intent)` turns
   the cryptographically-verified intent into the exact spend policy that bounds the signing key — so the same
   intent enforces the bound at **both** the AP2 layer and the x402 layer (defense in depth). The signing key
   stays in `X402Account` **below the model layer**.
4. **End-to-end settlement.** A verified mandate chain pays a real `X402PaymentGate`-fronted seller and gets
   `200` + an `X-PAYMENT-RESPONSE` receipt. An over-cap cart raises `Ap2PaymentDeniedException` **before any
   payment** — no signature, no money.
5. **Discovery.** `Ap2Extension.advertiseOn(card)` puts the AP2 extension URI under a live A2A AgentCard's
   `capabilities.extensions`, the standard AP2 discovery mechanism.

## What this is NOT (deferred)

- **Mandate issuance** — minting the user's signed VC (the wallet / credential-provider role) is deferred behind
  the same custody caution as buyer-side x402. The spike is **verify-first**.
- **Non-x402 rails** — cards / PayPal / bank rails are out of scope; we own the x402 rail.
- **Production A2A wiring** — advertising is shown via `Ap2Extension.advertiseOn(card)`; the production change is
  to thread an `extensions` parameter through `A2AServer.from` / `agentCard` (a one-line, localized edit — the
  card is already a plain map with a `capabilities` block).
- **Mandate schema** — the typed model here is a pragmatic read of `credentialSubject`; the canonical AP2
  mandate schema should be pinned against upstream before GA (the identity module made the same call about the
  AGNTCY VC envelope).

## Recommendation

**GO** for an AP2 verify-first epic, built as the `:agents-kt-ap2` module this spike scaffolds. It is the
coherent capstone of the agentic-commerce stack shipped in 0.8.1 (A2A + identity + x402) and rides a
foundation-credible standard (Google + the card networks, GA in the major clouds). Sequence it after x402
buyer-side hardening; gate it — like buyer-side x402 — on the spend-authority guardrails (here: the Intent
Mandate *is* the guardrail, signed and verifiable). Order within payments: x402 settlement *(shipped)* → **AP2
mandate-verify + x402-settle (this spike)** → mandate issuance *(deferred)*.

See [PRD §12.10](prd.md) for the strategic framing and [x402.md](x402.md) for the settlement layer underneath.
