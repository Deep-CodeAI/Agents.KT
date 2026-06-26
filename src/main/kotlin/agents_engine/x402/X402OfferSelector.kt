package agents_engine.x402

import java.math.BigInteger

/**
 * `agents_engine/x402/X402OfferSelector.kt` — #4528 (PRD §12.8). Chooses *which* of a `402`'s policy-permitted
 * `accepts[]` offers to pay. The seller controls the order of `accepts[]`, so paying the **first** acceptable
 * offer lets a seller steer the buyer toward the costliest one — the selector makes the choice the buyer's,
 * deterministically.
 *
 * [select] receives only offers the [X402SpendPolicy] already permits; returning null declines them all.
 */
fun interface X402OfferSelector {
    fun select(offers: List<PaymentRequirements>): PaymentRequirements?

    companion object {
        /** Default — least financial exposure: the lowest `maxAmountRequired`; ties resolve to the first. */
        val LowestAmount: X402OfferSelector = X402OfferSelector { offers ->
            offers.minByOrNull { it.maxAmountRequired.toBigIntegerOrNull() ?: BigInteger.ZERO }
        }

        /** Compatibility — the seller's first permitted offer (the pre-hardening behavior); opt in explicitly. */
        val FirstAllowed: X402OfferSelector = X402OfferSelector { offers -> offers.firstOrNull() }
    }
}
