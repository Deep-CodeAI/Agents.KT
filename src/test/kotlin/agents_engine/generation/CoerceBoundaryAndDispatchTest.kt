package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Second batch for #1975. Focuses on the residual mutants
// CoerceHelpersBranchTest didn't reach:
//
// - coerceToInt:590, 596   — Double/Float branch boundary at Int.MAX_VALUE.toDouble()
//                            (ConditionalsBoundary mutants)
// - coerceToLong:677, 683  — Float branch boundary at Long.MAX_VALUE.toDouble()
// - coerceValue:561        — Float dispatch path (private — via constructFromMap)
// - coerceValue:565        — List-without-type-parameter early-return (private)
// - hasGenerableAnnotation:619, 620  — KSP cache-hit short-circuit
//
// Tests exercise these via constructFromMap with @Generable wrappers — the
// helpers are private, so we drive them through the public surface.

@Generable("Int wrapper") data class IntWrap(val n: Int)
@Generable("Long wrapper") data class LongWrap(val n: Long)
@Generable("Double wrapper") data class DoubleWrap(val v: Double)
@Generable("Float wrapper") data class FloatWrap(val v: Float)
@Generable("Raw list wrapper") data class RawListWrap(val items: List<*>)
@Generable("Plain marker") data class PlainGenerable(val name: String)

class CoerceBoundaryAndDispatchTest {

    // ── coerceToInt — Double branch boundary (line 590) ───────────────────────

    @Test fun `coerceInt accepts Int_MAX_VALUE_toDouble exactly (Double branch)`() {
        // Kills ConditionalsBoundaryMutator on line 590:
        //   if (n > Int.MAX_VALUE.toDouble()) return null
        // The mutant flips `>` to `>=`. The mutated condition would reject
        // n == Int.MAX_VALUE.toDouble(). We assert it ACCEPTS this exact value.
        val result = IntWrap::class.constructFromMap(mapOf("n" to Int.MAX_VALUE.toDouble()))
        assertNotNull(result, "Int.MAX_VALUE as Double exactly must coerce successfully")
        assertEquals(Int.MAX_VALUE, result!!.n)
    }

    @Test fun `coerceInt accepts Int_MIN_VALUE_toDouble exactly (Double branch)`() {
        // Mirror — the lower boundary mutant.
        val result = IntWrap::class.constructFromMap(mapOf("n" to Int.MIN_VALUE.toDouble()))
        assertNotNull(result)
        assertEquals(Int.MIN_VALUE, result!!.n)
    }

    @Test fun `coerceInt rejects just-above Int_MAX as Double`() {
        // Anchors the boundary from the other side.
        val result = IntWrap::class.constructFromMap(
            mapOf("n" to Int.MAX_VALUE.toDouble() + 1.0)
        )
        assertNull(result, "Int.MAX_VALUE + 1.0 (Double) must reject")
    }

    @Test fun `coerceInt rejects just-below Int_MIN as Double`() {
        val result = IntWrap::class.constructFromMap(
            mapOf("n" to Int.MIN_VALUE.toDouble() - 1.0)
        )
        assertNull(result, "Int.MIN_VALUE - 1.0 (Double) must reject")
    }

    // ── coerceToInt — Float branch boundary (line 596) ────────────────────────

    @Test fun `coerceInt Float branch accepts whole value within range`() {
        // Kills ConditionalsBoundaryMutator on line 596 (Float branch's range check).
        // Float can't exactly represent Int.MAX_VALUE; use a value safely within
        // the Float's precision so the equality with floor(n) holds.
        val result = IntWrap::class.constructFromMap(mapOf("n" to 1_000_000.0f))
        assertNotNull(result)
        assertEquals(1_000_000, result!!.n)
    }

    @Test fun `coerceInt Float branch rejects huge value above Int_MAX`() {
        val result = IntWrap::class.constructFromMap(mapOf("n" to 1e10f))
        assertNull(result, "1e10 as Float exceeds Int.MAX_VALUE → reject")
    }

    @Test fun `coerceInt Float branch rejects huge negative value below Int_MIN`() {
        val result = IntWrap::class.constructFromMap(mapOf("n" to -1e10f))
        assertNull(result, "-1e10 as Float below Int.MIN_VALUE → reject")
    }

    // ── coerceToLong — Float branch boundary (lines 677, 683) ─────────────────

    @Test fun `coerceLong Float branch accepts whole value within range`() {
        val result = LongWrap::class.constructFromMap(mapOf("n" to 1_000_000.0f))
        assertNotNull(result)
        assertEquals(1_000_000L, result!!.n)
    }

    @Test fun `coerceLong Double branch boundary at large value within range`() {
        // 1e15 is exactly representable as Double (within mantissa precision)
        // AND well within Long range — covers the upper-branch happy path.
        val result = LongWrap::class.constructFromMap(mapOf("n" to 1e15))
        assertNotNull(result)
        assertEquals(1_000_000_000_000_000L, result!!.n)
    }

    @Test fun `coerceLong Double branch rejects out-of-Long-range`() {
        // 1e20 exceeds Long.MAX_VALUE (~9.2e18). Kills the boundary check.
        val result = LongWrap::class.constructFromMap(mapOf("n" to 1e20))
        assertNull(result)
    }

    @Test fun `coerceLong Float branch rejects out-of-Long-range`() {
        val result = LongWrap::class.constructFromMap(mapOf("n" to 1e20f))
        assertNull(result)
    }

    // ── coerceValue — Float dispatch path (line 561) ──────────────────────────

    @Test fun `coerceValue Float type accepts Number-as-Float`() {
        // Drives coerceValue line 561: Float::class -> (value as? Number)?.toFloat()
        val result = FloatWrap::class.constructFromMap(mapOf("v" to 3.14))
        assertNotNull(result)
        assertEquals(3.14f, result!!.v, 1e-3f)
    }

    @Test fun `coerceValue Float type rejects non-Number`() {
        // Drives the `(value as? Number)?` short-circuit at line 561.
        // The mutant that strips `as? Number` would NPE; current code returns null.
        val result = FloatWrap::class.constructFromMap(mapOf("v" to "3.14"))
        assertNull(result, "String value must not silently coerce to Float")
    }

    @Test fun `coerceValue Double type accepts Number-as-Double`() {
        val result = DoubleWrap::class.constructFromMap(mapOf("v" to 42))
        assertNotNull(result)
        assertEquals(42.0, result!!.v, 1e-9)
    }

    @Test fun `coerceValue Double type rejects non-Number`() {
        val result = DoubleWrap::class.constructFromMap(mapOf("v" to "42.0"))
        assertNull(result)
    }

    // ── coerceValue — List-without-type-parameter (line 565) ──────────────────

    @Test fun `coerceValue raw List (star-projected) returns items as-is`() {
        // Line 565: `val elementType = type.arguments.firstOrNull()?.type ?: return items`
        // The early return happens when the List has no type argument (raw or
        // star-projected). Test asserts items are passed through untouched.
        val result = RawListWrap::class.constructFromMap(
            mapOf("items" to listOf("a", 1, true, null))
        )
        assertNotNull(result)
        assertEquals(4, result!!.items.size)
        assertEquals("a", result.items[0])
        assertEquals(1, result.items[1])
        assertEquals(true, result.items[2])
        assertNull(result.items[3])
    }

    @Test fun `coerceValue raw List with empty input returns empty list`() {
        val result = RawListWrap::class.constructFromMap(mapOf("items" to emptyList<Any?>()))
        assertNotNull(result)
        assertEquals(0, result!!.items.size)
    }

    @Test fun `coerceValue raw List rejects non-List value`() {
        // Same line 565 — preceding `value as? List<*> ?: return null` is the
        // typed-cast guard. Kills the mutant that swaps `?:` for non-null assert.
        val result = RawListWrap::class.constructFromMap(mapOf("items" to "not a list"))
        assertNull(result)
    }

    // ── hasGenerableAnnotation (lines 619, 620) ───────────────────────────────

    @Test fun `hasGenerableAnnotation true for @Generable class via reflection fallback`() {
        // Line 620: `if (GeneratedMetaCache.lookupLlmDescription(this) != null) return true`
        // (and the reflection fallback after). Kills the boolean-return mutant.
        assertTrue(
            PlainGenerable::class.hasGenerableAnnotation(),
            "PlainGenerable carries @Generable; probe must return true",
        )
    }

    @Test fun `hasGenerableAnnotation false for non-Generable class`() {
        // Kills mutant that hardcodes true.
        assertEquals(
            false,
            String::class.hasGenerableAnnotation(),
            "String has no @Generable; probe must return false",
        )
    }
}
