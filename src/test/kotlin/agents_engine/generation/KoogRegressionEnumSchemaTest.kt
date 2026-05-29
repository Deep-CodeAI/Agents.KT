package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #2479 part 1 (under Koog regression epic #2474) — enum types in
 * tool/output schemas must be emitted with their declared constant
 * names verbatim, NOT lowercased or otherwise case-mutated.
 *
 * Koog signal: Anthropic client lowercased enum values, breaking
 * `@SerialName`-driven contracts. Agents.KT does not depend on
 * kotlinx.serialization, so the equivalent contract is "use
 * `Enum.name` verbatim" — i.e., preserve the source-defined casing
 * exactly.
 *
 * This pins two contracts:
 *
 * 1. Enum-typed parameters appear in the generated JSON Schema as a
 *    proper `{"type":"string","enum":[...]}` constraint — not the
 *    untyped `{"type":"string"}` fallback that silently accepted any
 *    value before #2479 part 1.
 * 2. Each declared constant name is emitted verbatim — capitalization,
 *    camelCase, anything goes — no provider adapter mutates it.
 */
class KoogRegressionEnumSchemaTest {

    enum class Priority { veryHigh, normal, low }

    enum class Color { RED, Green, blue }

    @Generable("Task with a priority enum")
    data class TaskArgs(
        @Guide("Task title") val title: String,
        @Guide("Priority level") val priority: Priority,
    )

    @Generable("Item with a color enum")
    data class ItemArgs(
        val color: Color,
    )

    @Test
    fun `enum field appears in schema with proper enum array, not plain string fallback`() {
        val schema = TaskArgs::class.jsonSchema()
        // The schema must declare the priority property as a string with an
        // enum constraint, not the generic catch-all.
        assertTrue(
            schema.contains(""""priority":{"type":"string","enum":["""),
            "expected `priority` rendered with an enum array; got: $schema",
        )
    }

    @Test
    fun `enum constant names are emitted verbatim — no lowercasing, no case mutation`() {
        val schema = TaskArgs::class.jsonSchema()
        // veryHigh stays veryHigh (NOT veryhigh, NOT VERYHIGH, NOT very_high)
        assertTrue(
            schema.contains("\"veryHigh\""),
            "veryHigh must be preserved exactly: $schema",
        )
        assertTrue(
            schema.contains("\"normal\""),
            "normal must appear: $schema",
        )
        assertTrue(
            schema.contains("\"low\""),
            "low must appear: $schema",
        )
        // Explicit anti-patterns from the Koog signal:
        assertTrue(
            !schema.contains("\"veryhigh\""),
            "veryhigh (lowercased) must NOT leak into the schema: $schema",
        )
        assertTrue(
            !schema.contains("\"VERYHIGH\""),
            "VERYHIGH (uppercased) must NOT leak into the schema: $schema",
        )
    }

    @Test
    fun `mixed-case enum constants survive intact across all values`() {
        val schema = ItemArgs::class.jsonSchema()
        // RED, Green, blue all carry their source casing into the schema.
        assertTrue(schema.contains("\"RED\""), "RED must stay uppercase: $schema")
        assertTrue(schema.contains("\"Green\""), "Green must stay TitleCase: $schema")
        assertTrue(schema.contains("\"blue\""), "blue must stay lowercase: $schema")
    }
}
