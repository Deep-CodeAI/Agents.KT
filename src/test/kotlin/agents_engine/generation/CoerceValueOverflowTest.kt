package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Tests for #855 — coerceValue rejects out-of-range or fractional inputs for
// Int / Long fields instead of silently truncating. Out-of-range routes to
// constructFromMap returning null, which surfaces as an invalidArgs error.

@Generable("box of an Int") data class IntBox(val n: Int)
@Generable("box of a Long") data class LongBox(val n: Long)

class CoerceValueOverflowTest {

    @Test
    fun `Int field rejects value larger than Int_MAX_VALUE`() {
        // 99_999_999_999 truncated to Int gives -1474836425 — silent corruption.
        val result = IntBox::class.constructFromMap(mapOf("n" to 99_999_999_999L))
        assertNull(result, "out-of-range Int must reject construction, not silently truncate")
    }

    @Test
    fun `Int field rejects value below Int_MIN_VALUE`() {
        val result = IntBox::class.constructFromMap(mapOf("n" to -99_999_999_999L))
        assertNull(result, "below-range Int must reject construction")
    }

    @Test
    fun `Int field accepts Int_MAX_VALUE exactly`() {
        val result = IntBox::class.constructFromMap(mapOf("n" to Int.MAX_VALUE.toLong()))
        assertNotNull(result)
        assertEquals(Int.MAX_VALUE, result!!.n)
    }

    @Test
    fun `Int field accepts Int_MIN_VALUE exactly`() {
        val result = IntBox::class.constructFromMap(mapOf("n" to Int.MIN_VALUE.toLong()))
        assertNotNull(result)
        assertEquals(Int.MIN_VALUE, result!!.n)
    }

    @Test
    fun `Int field rejects fractional Double input`() {
        val result = IntBox::class.constructFromMap(mapOf("n" to 1.5))
        assertNull(result, "fractional Double must reject coercion to Int")
    }

    @Test
    fun `Int field accepts whole-number Double`() {
        val result = IntBox::class.constructFromMap(mapOf("n" to 42.0))
        assertNotNull(result)
        assertEquals(42, result!!.n)
    }

    @Test
    fun `Int field rejects NaN and Infinity`() {
        assertNull(IntBox::class.constructFromMap(mapOf("n" to Double.NaN)))
        assertNull(IntBox::class.constructFromMap(mapOf("n" to Double.POSITIVE_INFINITY)))
        assertNull(IntBox::class.constructFromMap(mapOf("n" to Double.NEGATIVE_INFINITY)))
    }

    @Test
    fun `Long field rejects fractional Double input`() {
        val result = LongBox::class.constructFromMap(mapOf("n" to 1.5))
        assertNull(result, "fractional Double must reject coercion to Long")
    }

    @Test
    fun `Long field accepts integer-valued Double within range`() {
        val result = LongBox::class.constructFromMap(mapOf("n" to 9_000_000_000_000.0))
        assertNotNull(result)
        assertEquals(9_000_000_000_000L, result!!.n)
    }

    @Test
    fun `Long field rejects out-of-range Double`() {
        // 1e30 is well outside Long range.
        val result = LongBox::class.constructFromMap(mapOf("n" to 1e30))
        assertNull(result, "out-of-range Double must reject coercion to Long")
    }

    @Test
    fun `Long field accepts Int and Long values transparently`() {
        assertEquals(42L, LongBox::class.constructFromMap(mapOf("n" to 42))!!.n)
        assertEquals(42L, LongBox::class.constructFromMap(mapOf("n" to 42L))!!.n)
    }
}
