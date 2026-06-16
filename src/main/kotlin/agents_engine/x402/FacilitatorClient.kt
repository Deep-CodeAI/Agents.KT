package agents_engine.x402

/**
 * `agents_engine/x402/FacilitatorClient.kt` — #4527 (PRD §12.8). The x402 **facilitator** seam: the service
 * that cryptographically verifies a buyer's signed payment and settles it on-chain. Keeping it an interface
 * (a) keeps the seller crypto-free and key-free, (b) lets [X402PaymentGate] be tested hermetically with a
 * fake, and (c) means production stays on a **hosted** facilitator (e.g. Coinbase CDP) — the seller never
 * runs a custodial settler, which is what would trip money-transmitter regulation.
 *
 * [paymentHeader] is the raw value of the buyer's `X-PAYMENT` request header (base64-encoded payload).
 */
interface FacilitatorClient {
    /** Check the signed payment against [requirements] without moving money. */
    fun verify(paymentHeader: String, requirements: PaymentRequirements): FacilitatorVerification

    /** Submit the verified payment on-chain. */
    fun settle(paymentHeader: String, requirements: PaymentRequirements): FacilitatorSettlement
}
