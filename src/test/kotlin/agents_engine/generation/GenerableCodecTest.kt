package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #2803 — pins the [GenerableCodec] seam: the single boundary where a `KClass<*>` resolves to a typed
 * decoder (KSP-generated when present, else reflective). `constructFromMap` / `fromLlmOutput` /
 * `coerceValue` all route their deserialization through `codec()`, so the unchecked casts that used to
 * be sprinkled across them now live at this one place. These assert the decode contract directly.
 */
class GenerableCodecTest {

    @Generable("A point")
    data class Point(@Guide("x") val x: Int, @Guide("y") val y: Int)

    @Generable("A shape")
    sealed interface Shape {
        @Generable("circle") data class Circle(@Guide("r") val radius: Int) : Shape
        @Generable("nothing") data object None : Shape
    }

    @Test
    fun `codec decodes a data class field map into a typed instance`() {
        val decoded = Point::class.codec().decode(mapOf("x" to 3, "y" to 4))
        assertEquals(Point(3, 4), decoded)
    }

    @Test
    fun `codec on a sealed parent dispatches on the type discriminator`() {
        val decoded = Shape::class.codec().decode(mapOf("type" to "Circle", "radius" to 7))
        assertEquals(Shape.Circle(7), decoded)
    }

    @Test
    fun `codec resolves a data-object variant from its discriminator`() {
        val decoded = Shape::class.codec().decode(mapOf("type" to "None"))
        assertEquals(Shape.None, decoded)
    }

    @Test
    fun `codec returns null for an unknown sealed variant`() {
        assertNull(Shape::class.codec().decode(mapOf("type" to "Hexagon")))
    }

    @Test
    fun `codec rejects extra keys not in the constructor`() {
        assertNull(Point::class.codec().decode(mapOf("x" to 1, "y" to 2, "z" to 3)))
    }
}
