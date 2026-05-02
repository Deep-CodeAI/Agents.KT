package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Generable("strict") data class StrictArgs(val name: String, val count: Int = 0)

@Generable
sealed interface Geometry {
    @Generable data class CircleStrict(val radius: Double) : Geometry
    @Generable data class SquareStrict(val side: Double) : Geometry
}

/**
 * Tests for #665 — `constructFromMap` rejects extra keys (returns null) so the
 * `additionalProperties: false` schema contract is enforced provider-independently
 * at the Kotlin layer.
 */
class StrictArgsValidationTest {

    @Test
    fun `constructFromMap returns null when input map has extra keys`() {
        val result = StrictArgs::class.constructFromMap(
            mapOf("name" to "ok", "extra" to "shouldn't be here"),
        )
        assertNull(result, "constructFromMap must reject unknown keys")
    }

    @Test
    fun `constructFromMap with only known fields constructs successfully (regression)`() {
        val result = StrictArgs::class.constructFromMap(mapOf("name" to "ok", "count" to 7))
        assertEquals(StrictArgs(name = "ok", count = 7), result)
    }

    @Test
    fun `constructFromMap with subset of known fields uses defaults (regression)`() {
        // 'count' has a default — the input map omitting it is legal.
        val result = StrictArgs::class.constructFromMap(mapOf("name" to "ok"))
        assertEquals(StrictArgs(name = "ok", count = 0), result)
    }

    @Test
    fun `sealed variant rejects extra keys`() {
        val result = Geometry.CircleStrict::class.constructFromMap(
            mapOf("radius" to 5.0, "color" to "red"),  // 'color' isn't a CircleStrict field
        )
        assertNull(result, "sealed variant must reject unknown keys")
    }

    @Test
    fun `discriminator field 'type' is allowed alongside known fields on sealed variants`() {
        // When parsing a sealed-type response, the JSON includes "type": "CircleStrict"
        // as the discriminator. constructFromMap of CircleStrict should NOT treat 'type'
        // as an extra (it's part of the sealed-variant schema).
        val result = Geometry.CircleStrict::class.constructFromMap(
            mapOf("type" to "CircleStrict", "radius" to 5.0),
        )
        assertEquals(Geometry.CircleStrict(radius = 5.0), result)
    }
}
