---
description: Source-file knowledge for the agents-kt-identity module (agents_engine.agntcy.identity) — AGNTCY Identity badge verify/resolve (#4521, PRD §12.6, epic #4517). IdentityVerifier.verify(compactJws, jwks) validates a W3C VC badge secured with JOSE/JWS against an issuer JWKS, fail-closed via nimbus-jose-jwt (rejects alg=none, HMAC algorithm-confusion, expired, wrong/unknown key). IdentityResolver fetches /.well-known/jwks.json (+ vcs.json raw). Verify-only — issuance deferred. Lives in a feature module so nimbus stays out of core. Call when the IDE LLM reasons about agent trust / badge verification.
---

# `agents-kt-identity` — AGNTCY Identity badge verify (#4521)

The **trust** pillar of the AGNTCY epic (#4517), beside the OASF discovery record (`agents_engine.agntcy`,
§12.6) and A2A invocation (§12.5). In a trust-gated agent network you accept work only from agents whose
**badge** (a W3C Verifiable Credential) a known issuer signed.

```kotlin
val jwks = IdentityResolver().fetchJwks("https://issuer.example/.well-known/jwks.json")
val badge = IdentityVerifier.verify(compactJws, jwks) // throws BadgeVerificationException if untrustworthy
// badge.issuer / badge.subject / badge.credentialSubject are now safe to trust
```

## Why a separate module

`agents-kt-identity` carries the `nimbus-jose-jwt` dependency so **core stays dependency-free** (same
pattern as `agents-kt-rag`). Consumers who want badge verification add this module.

## Why not hand-rolled

Signature verification is trust-critical; a bug is a trust *bypass*. So verification delegates to the vetted
nimbus `DefaultJWTProcessor` + `JWSVerificationKeySelector` rather than parsing JWS by hand. The footguns it
closes, each covered by a negative test in `IdentityVerifierTest`:
- **`alg: none`** — an unsecured (plain) JWT is rejected (the key selector admits only signature algs).
- **algorithm confusion** — an `HS256` token signed with the public key as the HMAC secret is rejected
  (`HS*` is not in `DEFAULT_ALGORITHMS`; admitting it is the classic forge-with-the-public-key attack).
- **tamper / wrong key / unknown `kid`** — signature fails or no key is selected.
- **expiry / not-before** — `exp`/`nbf` checked by the default claims verifier.

`DEFAULT_ALGORITHMS` = ES256/384/512, RS256/384/512, PS256/384/512, EdDSA — asymmetric only.

## Verify-only

Issuance (key management, signing, vaults) is the heavy half and is **deferred** to the self-hosted stack
(PRD §12.6). This module is the cheap, high-value half.

## Resolve

`IdentityResolver.fetchJwks(url)` fetches + parses the issuer JWKS (bounded timeouts + response size cap, as
these are attacker-influenceable reads). `fetchText(url)` returns raw JSON for `vcs.json` / resolver metadata
— returned verbatim because the AGNTCY VC envelope is still settling upstream (the caller extracts the compact
JWS to hand to `verify`), so we don't bind to a not-yet-stable schema. A hostile JWKS host can at worst deny
service: `verify` always re-checks the signature.

## Files

- `IdentityVerifier.kt` — `verify()` (the fail-closed core).
- `IdentityResolver.kt` — `fetchJwks` / `fetchText`.
- `VerifiedBadge.kt` — the success result (construction implies validity).
- `BadgeVerificationException.kt` — every failure path.

## Related

- `agents_engine/agntcy/OasfRecord.kt` (core) — the OASF discovery sibling in the same epic.
- Remaining #4517 subtasks: DIR gRPC client (#4520), OASF import/validate (#4519).
