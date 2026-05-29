package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2482b (under Koog regression epic #2474) — when a typed schema field
 * expects an object / list / sealed union but the model emits a JSON
 * STRING containing that structure, the deserializer must parse the
 * string and coerce; bare String fields must NOT trigger this path
 * (otherwise an LLM string with curly braces in the prose would be
 * silently rewritten).
 *
 * Koog signal: "LLMs send nested objects as JSON strings; argument
 * coercion needed."
 *
 * Guard rails:
 * - String → @Generable object: parse + construct.
 * - String → List<T>: parse + element-coerce.
 * - String fields (where the schema expects a String): the String is
 *   the value, not a wrapper around something else.
 * - Unparseable JSON string for object/list field: fail (return null
 *   from constructFromMap, routes through onError.invalidArgs).
 */
class KoogRegressionStringifiedJsonCoercionTest {

    @Generable("Inner record")
    data class Address(@Guide("Street") val street: String, @Guide("Zip") val zip: Int)

    @Generable("Outer record with a nested @Generable field")
    data class Profile(
        @Guide("Display name") val name: String,
        @Guide("Postal address") val address: Address,
        @Guide("Aliases") val aliases: List<String>,
    )

    @Test
    fun `nested Generable field accepts a JSON-string value and parses it`() {
        // The model sends `address` as a stringified JSON instead of a nested map.
        // The deserializer must parse the string and construct Address.
        val p = Profile::class.constructFromMap(
            mapOf(
                "name" to "Ada",
                "address" to """{"street":"Bonn Str","zip":53113}""",  // <-- STRING, not Map
                "aliases" to listOf("countess"),
            )
        )
        val profile = assertNotNull(p, "stringified JSON for an @Generable field must coerce")
        assertEquals("Ada", profile.name)
        assertEquals(Address("Bonn Str", 53113), profile.address)
    }

    @Test
    fun `nested Generable field still accepts a normal Map value (regression safety)`() {
        // The native shape — a nested Map — must keep working.
        val p = Profile::class.constructFromMap(
            mapOf(
                "name" to "Bob",
                "address" to mapOf("street" to "X", "zip" to 1),
                "aliases" to emptyList<String>(),
            )
        )
        assertEquals(Address("X", 1), p?.address)
    }

    @Test
    fun `List field accepts a JSON-string value and parses it`() {
        // Some LLMs/providers send list-typed args as a stringified JSON array.
        val p = Profile::class.constructFromMap(
            mapOf(
                "name" to "Cara",
                "address" to mapOf("street" to "Y", "zip" to 2),
                "aliases" to """["a","b","c"]""",  // <-- STRING, not List
            )
        )
        assertEquals(listOf("a", "b", "c"), assertNotNull(p).aliases)
    }

    @Test
    fun `String fields are NOT JSON-decoded — the string IS the value`() {
        // CRITICAL guard: a String field whose value happens to contain JSON-looking
        // characters (`{`, `[`, quotes) must remain the literal string. If the
        // coercion silently parsed it, user prose like "The {weather} report" would
        // be corrupted.
        val p = Profile::class.constructFromMap(
            mapOf(
                "name" to """{"this":"looks","like":"JSON"}""",
                "address" to mapOf("street" to "z", "zip" to 0),
                "aliases" to emptyList<String>(),
            )
        )
        assertEquals(
            """{"this":"looks","like":"JSON"}""",
            assertNotNull(p).name,
            "String fields must NOT be JSON-decoded — the LLM string IS the value",
        )
    }

    @Test
    fun `malformed JSON string for an object field surfaces as a deserialization failure`() {
        // Per the guard: if the field expects an object and the string isn't
        // valid JSON, construction must fail (return null). The caller (typed
        // tool dispatch) routes this through onError.invalidArgs.
        val p = Profile::class.constructFromMap(
            mapOf(
                "name" to "n",
                "address" to "not even close to json {",
                "aliases" to emptyList<String>(),
            )
        )
        assertNull(p, "unparseable JSON for an object field must NOT silently succeed")
    }

    @Test
    fun `sealed union field accepts a JSON-string discriminated value`() {
        // The earlier sealed-dispatch fix (#2482a) plus stringified-JSON
        // coercion compose: a sealed-typed field accepts a JSON string
        // carrying the type discriminator.
        @Generable("Carrier with a sealed action field")
        data class Envelope(
            @Guide("The action to take") val action: KoogRegressionSealedDispatchTest.Action,
        )

        val env = Envelope::class.constructFromMap(
            mapOf("action" to """{"type":"SendMessage","text":"go"}""")
        )
        assertNotNull(env)
        assertTrue(env.action is KoogRegressionSealedDispatchTest.Action.SendMessage)
        assertEquals(
            "go",
            (env.action as KoogRegressionSealedDispatchTest.Action.SendMessage).text,
        )
    }
}
