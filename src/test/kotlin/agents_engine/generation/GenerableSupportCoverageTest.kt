package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for #885 — coverage gaps in GenerableSupport identified by PIT NO_COVERAGE.
//
// Three areas:
// 1. coerceToInt — Float input branches (lines 340-345)
// 2. coerceToLong — Float input branches (lines 366-371)
// 3. promptTypeName — List<T> recursion and KClass<*> simpleName fallback (lines 215-219)
//
// Reuses IntBox / LongBox fixtures from CoerceValueOverflowTest (in the same
// package and source set).

class GenerableSupportCoverageTest {

    // coerceToInt — Float input

    @Test
    fun `Int field accepts in-range whole-number Float`() {
        val r = IntBox::class.constructFromMap(mapOf("n" to 42.0f))
        assertNotNull(r)
        assertEquals(42, r!!.n)
    }

    @Test
    fun `Int field rejects fractional Float`() {
        assertNull(IntBox::class.constructFromMap(mapOf("n" to 1.5f)))
    }

    @Test
    fun `Int field rejects out-of-range Float`() {
        // 1e10 > Int.MAX_VALUE (~2.1e9)
        assertNull(IntBox::class.constructFromMap(mapOf("n" to 1.0e10f)))
    }

    @Test
    fun `Int field rejects NaN Float`() {
        assertNull(IntBox::class.constructFromMap(mapOf("n" to Float.NaN)))
    }

    @Test
    fun `Int field rejects Infinity Float`() {
        assertNull(IntBox::class.constructFromMap(mapOf("n" to Float.POSITIVE_INFINITY)))
        assertNull(IntBox::class.constructFromMap(mapOf("n" to Float.NEGATIVE_INFINITY)))
    }

    @Test
    fun `Int field accepts Short input`() {
        val r = IntBox::class.constructFromMap(mapOf("n" to 7.toShort()))
        assertNotNull(r)
        assertEquals(7, r!!.n)
    }

    @Test
    fun `Int field accepts Byte input`() {
        val r = IntBox::class.constructFromMap(mapOf("n" to 5.toByte()))
        assertNotNull(r)
        assertEquals(5, r!!.n)
    }

    // coerceToLong — Float input

    @Test
    fun `Long field accepts in-range whole-number Float`() {
        val r = LongBox::class.constructFromMap(mapOf("n" to 1000.0f))
        assertNotNull(r)
        assertEquals(1000L, r!!.n)
    }

    @Test
    fun `Long field rejects fractional Float`() {
        assertNull(LongBox::class.constructFromMap(mapOf("n" to 1.5f)))
    }

    @Test
    fun `Long field rejects NaN Float`() {
        assertNull(LongBox::class.constructFromMap(mapOf("n" to Float.NaN)))
    }

    @Test
    fun `Long field rejects Infinity Float`() {
        assertNull(LongBox::class.constructFromMap(mapOf("n" to Float.POSITIVE_INFINITY)))
        assertNull(LongBox::class.constructFromMap(mapOf("n" to Float.NEGATIVE_INFINITY)))
    }

    @Test
    fun `Long field accepts Short input`() {
        val r = LongBox::class.constructFromMap(mapOf("n" to 7.toShort()))
        assertNotNull(r)
        assertEquals(7L, r!!.n)
    }

    @Test
    fun `Long field accepts Byte input`() {
        val r = LongBox::class.constructFromMap(mapOf("n" to 5.toByte()))
        assertNotNull(r)
        assertEquals(5L, r!!.n)
    }

    // promptTypeName — List<T> branch and nested-Generable branch

    @Test
    fun `promptFragment renders List of primitives as List of T`() {
        // TaggedResult is from GenerableTest.kt: data class TaggedResult(@Guide val tags: List<String>, val count: Int)
        val out = TaggedResult::class.promptFragment()
        assertTrue(
            out.contains("<List<String>"),
            "List<String> should render as List<String> type name; got:\n$out",
        )
    }

    @Test
    fun `promptFragment renders nested Generable as its simpleName`() {
        // NestedResult is from GenerableTest.kt: data class NestedResult(@Guide val inner: ScoreResult, val label: String)
        val out = NestedResult::class.promptFragment()
        assertTrue(
            out.contains("ScoreResult"),
            "Nested @Generable should render with its simpleName; got:\n$out",
        )
    }
}
