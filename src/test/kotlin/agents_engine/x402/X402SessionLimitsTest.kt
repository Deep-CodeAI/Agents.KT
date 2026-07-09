package agents_engine.x402

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

// #4528 (PRD §12.8) — cross-payment velocity/exposure caps. The per-payment policy can't see the running
// total; these bound the aggregate so a run of individually-permitted payments can't exceed intended exposure.
class X402SessionLimitsTest {

    private val payee = "0xSeller"
    private fun storeWith(vararg entries: Pair<Long, Long>): InMemorySpendStore = InMemorySpendStore().apply {
        entries.forEach { (value, atMillis) -> record(payee, BigInteger.valueOf(value), atMillis) }
    }

    @Test
    fun `maxPayments caps the session payment count`() {
        val limits = X402SessionLimits(maxPayments = 2)
        val store = storeWith(100L to 0L, 100L to 0L) // already 2
        assertTrue("payment-count limit" in limits.reject(payee, BigInteger.TEN, store, 100)!!)
    }

    @Test
    fun `maxTotalValue caps cumulative spend`() {
        val limits = X402SessionLimits(maxTotalValue = BigInteger.valueOf(150))
        val store = storeWith(100L to 0L) // total 100
        assertTrue("maxTotalValue" in limits.reject(payee, BigInteger.valueOf(60), store, 100)!!) // 160 > 150
        assertNull(limits.reject(payee, BigInteger.valueOf(50), store, 100)) // 150 == cap, ok
    }

    @Test
    fun `maxPaymentsPerPayee caps payments to one recipient`() {
        val limits = X402SessionLimits(maxPaymentsPerPayee = 1)
        val store = storeWith(100L to 0L)
        assertTrue("per-payee" in limits.reject(payee, BigInteger.TEN, store, 100)!!)
        assertNull(limits.reject("0xDifferent", BigInteger.TEN, store, 100)) // another payee is fine
    }

    @Test
    fun `cooldown blocks payments inside the window`() {
        val limits = X402SessionLimits(cooldownMillis = 1000)
        val store = storeWith(100L to 5000L) // last payment at t=5000
        assertTrue("cooldown" in limits.reject(payee, BigInteger.TEN, store, 5500)!!) // 500ms < 1000
        assertNull(limits.reject(payee, BigInteger.TEN, store, 6000)) // 1000ms >= cooldown
    }

    @Test
    fun `no limits set permits everything`() {
        assertNull(X402SessionLimits().reject(payee, BigInteger.valueOf(999_999), InMemorySpendStore(), 0))
    }

    @Test
    fun `the in-memory store accumulates count, total and per-payee`() {
        val store = InMemorySpendStore()
        store.record("0xA", BigInteger.valueOf(100), 1)
        store.record("0xA", BigInteger.valueOf(50), 2)
        store.record("0xB", BigInteger.valueOf(25), 3)
        assertTrue(store.count() == 3)
        assertTrue(store.total() == BigInteger.valueOf(175))
        assertTrue(store.countForPayee("0xa") == 2) // case-insensitive
        assertTrue(store.lastPaymentMillis() == 3L)
    }
}
