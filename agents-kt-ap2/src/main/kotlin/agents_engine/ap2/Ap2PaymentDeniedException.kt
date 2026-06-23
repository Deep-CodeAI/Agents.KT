package agents_engine.ap2

/**
 * `agents_engine/ap2/Ap2PaymentDeniedException.kt` — AP2 (PRD §12.10) spike. The Cart Mandate is not authorized
 * by its Intent Mandate (amount over the cap, wrong merchant/asset/network, mismatched/expired intent), so the
 * buyer refuses before any payment. The safe terminal state — no x402 signature, no money moved. Mirrors
 * x402's own `X402PaymentDeniedException`, one layer up (mandate authorization vs. spend policy).
 */
class Ap2PaymentDeniedException(message: String) : RuntimeException(message)
