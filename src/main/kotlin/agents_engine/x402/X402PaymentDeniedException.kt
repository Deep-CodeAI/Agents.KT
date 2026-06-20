package agents_engine.x402

/**
 * `agents_engine/x402/X402PaymentDeniedException.kt` — #4528 (PRD §12.8). Thrown when the buyer refuses to
 * pay: an [X402SpendPolicy] vetoed every offer, no offer was acceptable (unsupported scheme / missing token
 * domain / unknown network), or the `402` carried no usable `accepts[]`.
 *
 * Distinct from [X402Exception] (a facilitator/transport failure) — this is the buyer's own guardrail saying
 * "not paying", and it is the safe terminal state: no signature was produced, no money moved.
 */
class X402PaymentDeniedException(message: String) : RuntimeException(message)
