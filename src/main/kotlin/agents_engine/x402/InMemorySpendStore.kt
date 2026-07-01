package agents_engine.x402

import java.math.BigInteger

/**
 * `agents_engine/x402/InMemorySpendStore.kt` — #4528 (PRD §12.8). Per-process [X402SpendStore] default —
 * thread-safe, but **not durable**: counters reset on restart. Fine for a single short-lived session; back
 * production deployments with a persistent store so a crash can't reset a cumulative spend cap.
 */
class InMemorySpendStore : X402SpendStore {
    private data class Entry(val payee: String, val value: BigInteger, val atMillis: Long)

    private val entries = mutableListOf<Entry>()
    private val lock = Any()

    override fun record(payee: String, value: BigInteger, atMillis: Long) {
        synchronized(lock) { entries += Entry(payee.lowercase(), value, atMillis) }
    }

    override fun count(): Int = synchronized(lock) { entries.size }

    override fun total(): BigInteger =
        synchronized(lock) { entries.fold(BigInteger.ZERO) { acc, e -> acc + e.value } }

    override fun countForPayee(payee: String): Int =
        synchronized(lock) { entries.count { it.payee == payee.lowercase() } }

    override fun lastPaymentMillis(): Long? = synchronized(lock) { entries.maxOfOrNull { it.atMillis } }
}
