package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for #1975 — branch coverage on the @PublishedApi coerce* helpers used
// by KSP-generated constructFromMap (#1704). Each helper has the shape:
//   null         → null
//   correct-type → cast through
//   wrong-type   → null (strict)
// Plus Int / Long get range-rejection paths inherited from coerceToInt /
// coerceToLong (#855).
//
// Existing CoerceValueOverflowTest goes through constructFromMap indirection;
// this file calls the helpers DIRECTLY so the mutants in the helper bodies
// surface, not just the constructFromMap dispatch.
//
// Targets PIT mutants on:
//   coerceString    (2 unkilled)
//   coerceInt       (2 unkilled)
//   coerceLong      (2 unkilled, plus 6 in coerceToLong)
//   coerceDouble    (3 unkilled)
//   coerceFloat     (3 unkilled)
//   coerceBoolean   (3 unkilled)
//   coerceList      (6 unkilled)
//   coerceToInt     (6 unkilled, reached via coerceInt)
//   coerceToLong    (reached via coerceLong)
class CoerceHelpersBranchTest {

    // ── coerceString ──────────────────────────────────────────────────────────

    @Test fun `coerceString null input returns null`() {
        assertNull(coerceString(null))
    }

    @Test fun `coerceString String input returns the same string`() {
        assertEquals("hello", coerceString("hello"))
    }

    @Test fun `coerceString non-String input uses toString`() {
        // Kills the mutant that removes the .toString() call.
        assertEquals("42", coerceString(42))
        assertEquals("true", coerceString(true))
        assertEquals("3.14", coerceString(3.14))
    }

    @Test fun `coerceString empty string returns empty string (not null)`() {
        // Distinguishes "value present but empty" from "value absent".
        assertEquals("", coerceString(""))
    }

    // ── coerceInt + coerceToInt (the workhorse) ───────────────────────────────

    @Test fun `coerceInt null input returns null`() {
        assertNull(coerceInt(null))
    }

    @Test fun `coerceInt non-Number input returns null`() {
        // Kills the mutant that flips `value as? Number ?: return null` to a cast.
        assertNull(coerceInt("42"))   // String, not Number
        assertNull(coerceInt(true))   // Boolean
        assertNull(coerceInt(listOf(1)))
    }

    @Test fun `coerceInt valid Int round-trips`() {
        assertEquals(42, coerceInt(42))
        assertEquals(0, coerceInt(0))
        assertEquals(-1, coerceInt(-1))
    }

    @Test fun `coerceInt valid Long in Int range round-trips`() {
        assertEquals(42, coerceInt(42L))
    }

    @Test fun `coerceInt Long_MAX_VALUE rejected (overflow)`() {
        assertNull(coerceInt(Long.MAX_VALUE))
    }

    @Test fun `coerceInt Int_MAX_VALUE as Long accepted`() {
        // Boundary: must accept exactly MAX, must reject MAX+1.
        assertEquals(Int.MAX_VALUE, coerceInt(Int.MAX_VALUE.toLong()))
    }

    @Test fun `coerceInt Int_MAX_VALUE plus one as Long rejected`() {
        assertNull(coerceInt(Int.MAX_VALUE.toLong() + 1))
    }

    @Test fun `coerceInt Int_MIN_VALUE as Long accepted`() {
        assertEquals(Int.MIN_VALUE, coerceInt(Int.MIN_VALUE.toLong()))
    }

    @Test fun `coerceInt Int_MIN_VALUE minus one as Long rejected`() {
        assertNull(coerceInt(Int.MIN_VALUE.toLong() - 1))
    }

    @Test fun `coerceInt whole-valued Double accepted`() {
        // 1.0 has no fractional part → coerce succeeds.
        assertEquals(1, coerceInt(1.0))
        assertEquals(42, coerceInt(42.0))
    }

    @Test fun `coerceInt fractional Double rejected`() {
        // 1.5 != floor(1.5) → reject.
        assertNull(coerceInt(1.5))
        assertNull(coerceInt(0.0001))
    }

    @Test fun `coerceInt NaN rejected`() {
        assertNull(coerceInt(Double.NaN))
    }

    @Test fun `coerceInt positive infinity rejected`() {
        assertNull(coerceInt(Double.POSITIVE_INFINITY))
    }

    @Test fun `coerceInt negative infinity rejected`() {
        assertNull(coerceInt(Double.NEGATIVE_INFINITY))
    }

    @Test fun `coerceInt huge Double rejected`() {
        assertNull(coerceInt(1e20))
        assertNull(coerceInt(-1e20))
    }

    @Test fun `coerceInt Float branch — whole value accepted`() {
        assertEquals(1, coerceInt(1.0f))
    }

    @Test fun `coerceInt Float branch — fractional rejected`() {
        assertNull(coerceInt(1.5f))
    }

    @Test fun `coerceInt Float branch — out-of-range rejected`() {
        assertNull(coerceInt(Float.MAX_VALUE))
    }

    @Test fun `coerceInt Short accepted`() {
        assertEquals(42, coerceInt(42.toShort()))
    }

    @Test fun `coerceInt Byte accepted`() {
        assertEquals(42, coerceInt(42.toByte()))
    }

    // ── coerceLong + coerceToLong ─────────────────────────────────────────────

    @Test fun `coerceLong null returns null`() {
        assertNull(coerceLong(null))
    }

    @Test fun `coerceLong non-Number returns null`() {
        assertNull(coerceLong("42"))
        assertNull(coerceLong(true))
    }

    @Test fun `coerceLong Long round-trips`() {
        assertEquals(42L, coerceLong(42L))
        assertEquals(Long.MAX_VALUE, coerceLong(Long.MAX_VALUE))
        assertEquals(Long.MIN_VALUE, coerceLong(Long.MIN_VALUE))
    }

    @Test fun `coerceLong Int widens to Long`() {
        assertEquals(42L, coerceLong(42))
    }

    @Test fun `coerceLong whole Double accepted`() {
        assertEquals(42L, coerceLong(42.0))
    }

    @Test fun `coerceLong fractional Double rejected`() {
        assertNull(coerceLong(1.5))
    }

    @Test fun `coerceLong NaN rejected`() {
        assertNull(coerceLong(Double.NaN))
    }

    @Test fun `coerceLong infinity rejected`() {
        assertNull(coerceLong(Double.POSITIVE_INFINITY))
        assertNull(coerceLong(Double.NEGATIVE_INFINITY))
    }

    @Test fun `coerceLong out-of-range Double rejected`() {
        // 1e30 > Long.MAX_VALUE.toDouble() — overflow path.
        assertNull(coerceLong(1e30))
        assertNull(coerceLong(-1e30))
    }

    @Test fun `coerceLong Float branch`() {
        assertEquals(42L, coerceLong(42.0f))
        assertNull(coerceLong(1.5f))
        assertNull(coerceLong(Float.NaN))
    }

    @Test fun `coerceLong Short Byte widening`() {
        assertEquals(42L, coerceLong(42.toShort()))
        assertEquals(42L, coerceLong(42.toByte()))
    }

    // ── coerceDouble ──────────────────────────────────────────────────────────

    @Test fun `coerceDouble null returns null`() {
        assertNull(coerceDouble(null))
    }

    @Test fun `coerceDouble Number round-trips`() {
        assertEquals(3.14, coerceDouble(3.14)!!, 1e-9)
        assertEquals(42.0, coerceDouble(42)!!, 1e-9)
        assertEquals(42.0, coerceDouble(42L)!!, 1e-9)
    }

    @Test fun `coerceDouble non-Number returns null`() {
        // Kills mutant that drops the `as? Number` and just casts.
        assertNull(coerceDouble("3.14"))
        assertNull(coerceDouble(true))
        assertNull(coerceDouble(listOf(3.14)))
    }

    @Test fun `coerceDouble NaN and infinity pass through (Numbers all the same)`() {
        // The contract is "value as Number then toDouble"; we don't filter
        // NaN/inf at this layer. Catches the mutant that adds an isFinite check.
        assertTrue(coerceDouble(Double.NaN)!!.isNaN())
        assertTrue(coerceDouble(Double.POSITIVE_INFINITY)!!.isInfinite())
    }

    // ── coerceFloat ───────────────────────────────────────────────────────────

    @Test fun `coerceFloat null returns null`() {
        assertNull(coerceFloat(null))
    }

    @Test fun `coerceFloat Number round-trips`() {
        assertEquals(3.14f, coerceFloat(3.14)!!, 1e-3f)
        assertEquals(42.0f, coerceFloat(42)!!, 1e-6f)
    }

    @Test fun `coerceFloat non-Number returns null`() {
        assertNull(coerceFloat("3.14"))
        assertNull(coerceFloat(true))
    }

    // ── coerceBoolean ─────────────────────────────────────────────────────────

    @Test fun `coerceBoolean null returns null`() {
        assertNull(coerceBoolean(null))
    }

    @Test fun `coerceBoolean Boolean round-trips`() {
        assertEquals(true, coerceBoolean(true))
        assertEquals(false, coerceBoolean(false))
    }

    @Test fun `coerceBoolean non-Boolean returns null (no String coercion)`() {
        // Strict — "true" / "false" / 1 / 0 are NOT coerced.
        // Kills the mutant that adds a String parse fallback.
        assertNull(coerceBoolean("true"))
        assertNull(coerceBoolean("false"))
        assertNull(coerceBoolean(1))
        assertNull(coerceBoolean(0))
    }

    // ── coerceList ────────────────────────────────────────────────────────────

    @Test fun `coerceList null returns null`() {
        assertNull(coerceList<String>(null) { it?.toString() })
    }

    @Test fun `coerceList non-List returns null`() {
        assertNull(coerceList<String>("not a list") { it?.toString() })
        assertNull(coerceList<String>(42) { it?.toString() })
        assertNull(coerceList<String>(mapOf("k" to "v")) { it?.toString() })
    }

    @Test fun `coerceList empty list returns empty list`() {
        val result = coerceList<String>(emptyList<Any?>()) { it?.toString() }
        assertNotNull(result)
        assertEquals(0, result!!.size)
    }

    @Test fun `coerceList all items coerce successfully`() {
        val result = coerceList<Int>(listOf(1, 2L, 3.0)) { coerceInt(it) }
        assertNotNull(result)
        assertEquals(listOf(1, 2, 3), result)
    }

    @Test fun `coerceList one item fails coercion returns null for whole list`() {
        // Kills the mutant that swaps `return null` for `continue` (silent skip).
        val result = coerceList<Int>(listOf(1, "bad", 3)) { coerceInt(it) }
        assertNull(result, "any item's coercion failure must fail the whole list")
    }

    @Test fun `coerceList preserves item order`() {
        val result = coerceList<Int>(listOf(3, 1, 4, 1, 5, 9, 2, 6)) { coerceInt(it) }
        assertEquals(listOf(3, 1, 4, 1, 5, 9, 2, 6), result)
    }
}
