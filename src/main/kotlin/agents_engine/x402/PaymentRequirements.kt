package agents_engine.x402

/**
 * `agents_engine/x402/PaymentRequirements.kt` — #4527 (PRD §12.8). The seller's terms for a paid resource,
 * sent in the `402 Payment Required` body of the [x402](https://github.com/x402-foundation/x402) protocol.
 * A buyer signs an [EIP-3009 `transferWithAuthorization`](https://eips.ethereum.org/EIPS/eip-3009) against
 * these terms; a facilitator verifies + settles it on-chain (gasless). **The seller holds no key and takes no
 * custody** — [payTo] is just a public recipient address, not a secret.
 *
 * @property network settlement network, e.g. `"base"` / `"base-sepolia"` / `"solana"`.
 * @property maxAmountRequired price in the asset's atomic units, as a decimal string (USDC has 6 decimals).
 * @property payTo recipient address (public; config, not a secret).
 * @property asset token contract address (e.g. USDC on the chosen network).
 * @property resource the URL being paid for.
 * @property scheme x402 payment scheme — `"exact"` (fixed price) or `"upto"` (metered cap).
 */
data class PaymentRequirements(
    val network: String,
    val maxAmountRequired: String,
    val payTo: String,
    val asset: String,
    val resource: String,
    val scheme: String = "exact",
    val description: String = "",
    val mimeType: String = "",
    val maxTimeoutSeconds: Int = 60,
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** The x402 wire object (key order fixed) for the `402` `accepts[]` and facilitator requests. */
    internal fun toJsonObject(): LinkedHashMap<String, Any?> = linkedMapOf(
        "scheme" to scheme,
        "network" to network,
        "maxAmountRequired" to maxAmountRequired,
        "resource" to resource,
        "description" to description,
        "mimeType" to mimeType,
        "payTo" to payTo,
        "maxTimeoutSeconds" to maxTimeoutSeconds,
        "asset" to asset,
        "extra" to extra,
    )
}
