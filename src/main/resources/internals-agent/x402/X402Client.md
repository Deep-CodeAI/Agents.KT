---
description: Source-file knowledge for agents_engine/x402/X402Client.kt + X402Account.kt + crypto/* — BUYER-side x402 agent payments (#4528, epic #4526, PRD §12.8). X402Client drives request->402->pay->retry; X402Account holds the signing key BELOW the model layer and signs an EIP-712/EIP-3009 transferWithAuthorization into the X-PAYMENT header, but only after an X402SpendPolicy guardrail (per-payment cap, payTo allowlist, network allowlist, HITL confirm) permits it. Real secp256k1/Keccak signing via BouncyCastle, pinned byte-for-byte against ethers.js vectors. EXPERIMENTAL — moves irreversible USDC. Call when the IDE LLM reasons about an agent autonomously paying for a resource / x402 buyer side.
---

# `agents_engine/x402/` — buyer-side x402 payments (#4528)

The **buyer (risky) half** of [x402](https://github.com/x402-foundation/x402): let an agent autonomously pay
for a resource it wants. Counterpart to the seller-side `X402PaymentGate` (see `X402PaymentGate.md`). This is
the half where **irreversible** money moves, so the design is guardrails-first.

```kotlin
val account = X402Account.fromPrivateKey(
    privateKeyHex = System.getenv("X402_KEY"),                 // below the model layer — never in a prompt
    policy = X402SpendPolicy(
        maxValuePerPayment = BigInteger.valueOf(10_000),       // 0.01 USDC cap (6 decimals)
        allowedPayTo = setOf("0xSeller"),                      // pin known sellers
        confirm = { plan -> humanApproves(plan) },             // optional HITL gate
    ),
)
val client = X402Client(account)
val resp = client.get("https://seller.example/premium")       // 402 -> sign -> retry -> 200
```

## Why guardrails-first (the threat model)

x402 moves irreversible money and the canonical failure is a prompt-injected agent draining a wallet
(Grok/Bankr ≈ \$150–200k, Freysa \$47k are confirmed-real). So:

- **The key is below the model layer.** It lives in [X402Account], constructed in operator code, never
  serialized/logged/placed in a prompt. The LLM drives the *request* but cannot read the key or widen the policy.
- **Every payment passes [X402SpendPolicy] before any signature.** `maxValuePerPayment` (the blast-radius cap),
  `allowedNetworks`, `allowedPayTo` (neutralizes a redirected-`payTo` injection), and a `confirm` HITL gate.
  `X402Account.reasonCannotPay()` returns the first failing reason; `authorize()` throws
  [X402PaymentDeniedException] rather than sign. No signature ⇒ no money moved — the safe terminal state.

## Flow (`X402Client.send`)

1. Send the request as-is. Non-`402` → return unchanged (don't pay for free resources).
2. On `402`, parse `accepts[]` → `PaymentRequirements.fromJsonObject`. Pick the **first** offer with
   `account.reasonCannotPay(offer) == null` (scheme `exact`, token EIP-712 domain present, known chainId,
   policy ok). None payable → `X402PaymentDeniedException` listing why each was skipped.
3. `account.authorize(offer)` builds an EIP-3009 `transferWithAuthorization` (`from`=buyer, `to`=`payTo`,
   `value`=`maxAmountRequired`, `validBefore`=now+`maxTimeoutSeconds`, random 32-byte `nonce`), signs the
   EIP-712 digest, and base64-packs the x402 v1 `X-PAYMENT` envelope `{x402Version, scheme, network,
   payload:{signature, authorization}}`.
4. Replay the original request (`HttpRequest.newBuilder(request)` preserves method/body/timeout) with the
   `X-PAYMENT` header. Return the paid response; read `X-PAYMENT-RESPONSE` for the settlement receipt.

## Crypto (`crypto/` — the one runtime use of BouncyCastle)

- `Keccak256` — **legacy Keccak-256**, NOT SHA3-256 (different padding; the JDK ships only SHA3). bcprov's
  `KeccakDigest`. Promoted bcprov `compileOnly → implementation` in `build.gradle.kts` (#4528).
- `Secp256k1` — address derivation (`keccak256(pubKeyXY)[12:]`), RFC-6979 deterministic ECDSA, **low-s** (EIP-2),
  recovery byte `v ∈ {27,28}` via point recovery; `recoverAddress` = `ecrecover` (used to round-trip verify).
- `Eip712` — `keccak256(0x1901 ‖ domainSeparator ‖ structHash)` for `TransferWithAuthorization` only (no
  general encoder). `Eip712Domain` = token name/version (from `requirements.extra`) + chainId + asset address.
- **Correctness is pinned byte-for-byte** against ethers.js v6 vectors in `X402CryptoTest` (keccak256("")
  anchor, EIP-3009 typehash `0x7c7c6cdb…`, privkey=1 address, domain/struct/digest, and the exact 65-byte
  signature). If those drift, a real facilitator would silently reject the payment.

## Files

- `X402Client.kt` — the `request→402→pay→retry` HTTP driver.
- `X402Account.kt` — key holder; `reasonCannotPay` / `authorize` (build+sign+pack the header). `SignedPayment` = result.
- `X402SpendPolicy.kt` — the guardrails. `PaymentPlan` = the HITL summary. `X402PaymentDeniedException` = the veto.
- `PaymentAuthorization.kt` — the EIP-3009 message + random nonce.
- `crypto/` — `Keccak256`, `Secp256k1`, `EcdsaSignature`, `Eip712`, `Eip712Domain`, `Hex`.

## Scope / follow-ups

Shipped EXPERIMENTAL: autonomous EVM `exact`-scheme payment with policy guardrails + optional HITL, verified
end-to-end against the real `X402PaymentGate` (a test facilitator ecrecovers the signer). NOT yet: scoped
ERC-4337 session keys (on-chain caps enforced by the key itself, the strongest guardrail — policy here is
in-process), the `upto` metered scheme, Solana, velocity/rate limits across payments, and an agent-tool wrapper
(`payForResource` tool). Token EIP-712 `name`/`version` come from the seller's `extra`; verify against the live
token contract before production.
