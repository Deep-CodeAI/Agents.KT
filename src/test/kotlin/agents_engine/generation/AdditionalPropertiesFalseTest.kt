package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertTrue

@Generable("plain") data class PlainArgs(val x: String, val y: Int = 0)

@Generable
sealed interface Shape {
    @Generable data class CircleVariant(val radius: Double) : Shape
    @Generable data class SquareVariant(val side: Double) : Shape
}

/**
 * Tests for #661 — strict JSON Schema for typed @Generable args:
 * `additionalProperties: false` so providers can constrain the model and
 * the executor never silently sees keys it doesn't expect.
 */
class AdditionalPropertiesFalseTest {

    @Test
    fun `data class jsonSchema includes additionalProperties false`() {
        val schema = PlainArgs::class.jsonSchema()
        assertTrue(schema.contains("\"additionalProperties\":false"),
            "schema must declare additionalProperties:false; got: $schema")
    }

    @Test
    fun `sealed variants each include additionalProperties false`() {
        val schema = Shape::class.jsonSchema()
        // The sealed schema is a oneOf of variant objects; each variant should be strict.
        // At least two `additionalProperties:false` occurrences (one per variant).
        val occurrences = "additionalProperties\":false".toRegex().findAll(schema).count()
        assertTrue(occurrences >= 2,
            "each sealed variant must declare additionalProperties:false; got $occurrences in: $schema")
    }
}
