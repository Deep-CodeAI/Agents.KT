---
description: Source-file knowledge for the :agents-kt-ap2 spike module (PRD §12.10) — AP2 (Agent Payments Protocol) mandate authorization layer. Composes three SHIPPED pieces: :agents-kt-identity JWS-VC verify + A2A AgentCard + x402 settlement. MandateVerifier verifies Intent/Cart mandates via IdentityVerifier (a mandate IS a VC); Ap2Buyer turns the Intent Mandate into an X402SpendPolicy and settles the cart over X402Client. Verify-first; mandate issuance deferred. Call when the IDE LLM reasons about agent-authorized payments / AP2 / mandates.
---

# `:agents-kt-ap2` — AP2 mandate authorization layer (spike, PRD §12.10)

AP2 (Google, an **A2A extension**) is the **authorization** layer over a settlement rail: signed **mandates**
prove *the human authorized the agent to buy*; x402 (§12.8) moves the money. This module is the
feasibility-spike proof that AP2 = **composition of pieces agents.kt already ships**, not a new protocol. See
`docs/ap2-feasibility.md`.

```kotlin
val intent = MandateVerifier.verifyIntent(intentJws, jwks)   // a JWS VC, verified via :agents-kt-identity
val cart   = MandateVerifier.verifyCart(cartJws, jwks)
val receipt = Ap2Buyer(signerKeyHex).pay(intent, cart)       // authz-chain check -> x402 settle -> Ap2Receipt
```

## The 1 + 1 = 3 (why this is assembly, not invention)

- **Mandates ARE Verifiable Credentials.** `MandateVerifier` calls the shipped `IdentityVerifier.verify(jws,
  jwks)` (the AGNTCY badge verifier, `:agents-kt-identity`) and reads `IntentMandate`/`CartMandate` out of
  `VerifiedBadge.credentialSubject`. The forgery surface (expiry / tamper / `alg:none` / `HS*`-confusion /
  wrong-key, all fail-closed) is inherited for free.
- **A2A is the transport.** `Ap2Extension.advertiseOn(card)` adds the AP2 extension URI to an A2A AgentCard's
  `capabilities.extensions` — the standard AP2 discovery mechanism.
- **x402 is the rail.** `Ap2Buyer` derives an `X402SpendPolicy` from the Intent Mandate and settles via
  `X402Client`.

## The authorization chain (the load-bearing AP2 rule)

`MandateVerifier.reasonNotAuthorized(cart, intent)` — a Cart Mandate is payable only if its Intent Mandate
authorized *this* spend: same `intentId`, `amount ≤ maxAmount`, allowed merchant, matching asset/network.
Enforced **before** x402. Then `Ap2Buyer.spendPolicyFrom(intent)` re-enforces the *same* bound at the x402 layer
(`maxValuePerPayment` / `allowedPayTo` / `allowedNetworks`) — **defense in depth**, both fed by the one signed
intent. This is the PRD's "Intent Mandate ≈ a scoped, signed, verifiable `X402SpendPolicy`" made concrete. The
signing key lives in `X402Account` **below the model layer**; a cart the intent doesn't cover raises
`Ap2PaymentDeniedException` (no signature, no money).

## Files

- `MandateVerifier.kt` — verify (reuse `IdentityVerifier`) + the authorization-chain check.
- `IntentMandate.kt` / `CartMandate.kt` — typed reads of the VC `credentialSubject`. `Ap2MandateException` on malformed.
- `Ap2Buyer.kt` — `spendPolicyFrom(intent)` + `pay(intent, cart)` (authz → x402 settle). `Ap2Receipt` = outcome.
- `Ap2Extension.kt` — the AP2 extension URI + `advertiseOn(card)`. `Ap2PaymentDeniedException` = the buyer's veto.

## Scope / deferred

Verify-first. **Mandate issuance** (minting the user's signed VC — the wallet role) is deferred behind the same
custody caution as buyer-side x402; non-x402 rails (cards/PayPal) are out of scope; production A2A wiring =
thread an `extensions` param through `A2AServer.from`/`agentCard`. Pin the canonical AP2 mandate schema against
upstream before GA.

## Related

- `agntcy/identity/IdentityVerifier.md` — the VC verifier reused here.
- `x402/X402Client.md` — the settlement rail underneath.
