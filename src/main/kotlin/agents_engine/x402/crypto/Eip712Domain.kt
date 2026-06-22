package agents_engine.x402.crypto

/**
 * `agents_engine/x402/crypto/Eip712Domain.kt` — #4528 (PRD §12.8). The EIP-712 domain of the payment token
 * (e.g. USDC), the `domainSeparator` half of the digest built by [Eip712].
 *
 * `name`/`version` are the token contract's own EIP-712 domain values (USDC on Base = `"USD Coin"` / `"2"`);
 * they vary per token and are advertised by sellers in x402 `PaymentRequirements.extra`. [verifyingContract]
 * is the token address; [chainId] identifies the settlement network.
 */
internal data class Eip712Domain(
    val name: String,
    val version: String,
    val chainId: Long,
    val verifyingContract: String,
)
