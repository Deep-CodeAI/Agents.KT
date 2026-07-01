package agents_engine.x402

import java.math.BigInteger

/**
 * `agents_engine/x402/X402SessionLimits.kt` — #4528 (PRD §12.8). **Cross-payment** velocity/exposure caps,
 * evaluated against an [X402SpendStore] before each payment. The per-payment [X402SpendPolicy] bounds *one*
 * authorization; these bound the *aggregate* — so a run of individually-permitted payments can't quietly
 * exceed the operator's total intended exposure. Enforced by [X402Client].
 *
 * Every limit is opt-in; all set limits must pass.
 *
 * @property maxPayments cap on the number of settled payments in the session/window; null = no cap.
 * @property maxTotalValue cap on cumulative settled value (atomic units); a payment that would cross it is
 *   refused; null = no cap.
 * @property maxPaymentsPerPayee cap on settled payments to any single recipient; null = no cap.
 * @property cooldownMillis minimum gap between payments; a payment within the cooldown of the last is refused;
 *   null = no cooldown.
 */
data class X402SessionLimits(
    val maxPayments: Int? = null,
    val maxTotalValue: BigInteger? = null,
    val maxPaymentsPerPayee: Int? = null,
    val cooldownMillis: Long? = null,
) {
    /** Why a payment of [value] to [payee] now ([nowMillis]) is refused given [store], or null if allowed. */
    internal fun reject(payee: String, value: BigInteger, store: X402SpendStore, nowMillis: Long): String? {
        if (maxPayments != null && store.count() >= maxPayments) {
            return "session payment-count limit ($maxPayments) reached"
        }
        if (maxTotalValue != null && store.total() + value > maxTotalValue) {
            return "payment would push session total ${store.total() + value} over maxTotalValue $maxTotalValue"
        }
        if (maxPaymentsPerPayee != null && store.countForPayee(payee) >= maxPaymentsPerPayee) {
            return "per-payee payment limit ($maxPaymentsPerPayee) for '$payee' reached"
        }
        val last = store.lastPaymentMillis()
        if (cooldownMillis != null && last != null && nowMillis - last < cooldownMillis) {
            return "cooldown active — ${cooldownMillis - (nowMillis - last)}ms remaining"
        }
        return null
    }
}
