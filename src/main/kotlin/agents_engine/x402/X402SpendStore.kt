package agents_engine.x402

import java.math.BigInteger

/**
 * `agents_engine/x402/X402SpendStore.kt` — #4528 (PRD §12.8). Records settled payments so [X402SessionLimits]
 * can enforce **cross-payment** caps (a sequence of individually-permitted payments must not exceed an
 * operator's intended total exposure — the per-payment [X402SpendPolicy] can't see the running total).
 *
 * The default [InMemorySpendStore] is per-process; a production deployment should back this with a **durable**
 * store so the counters survive a restart (otherwise a crash-loop resets the cumulative cap).
 */
interface X402SpendStore {
    /** Record a settled payment of [value] to [payee] at [atMillis]. */
    fun record(payee: String, value: BigInteger, atMillis: Long)

    /** Total number of settled payments in this session/window. */
    fun count(): Int

    /** Cumulative settled value in this session/window (atomic units). */
    fun total(): BigInteger

    /** Number of settled payments to [payee] (case-insensitive). */
    fun countForPayee(payee: String): Int

    /** Epoch-millis of the most recent settled payment, or null if none yet. */
    fun lastPaymentMillis(): Long?
}
