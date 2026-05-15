package smoke

import agents_engine.generation.Generable
import agents_engine.generation.fromLlmOutput
import agents_engine.generation.jsonSchema
import agents_engine.generation.toLlmDescription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// #1718 — v0.4.6 smoke test. This subproject excludes kotlin-reflect from
// every classpath via `configurations.all { exclude(...) }`. The body of
// the suite proves three things:
//
//   1. kotlin-reflect really IS absent (negative classpath assertion).
//   2. Consumers with a generated `__GeneratedSchema` companion get full
//      functionality — schema, LLM description, constructor — via the
//      KSP-aware cache path (no kotlin-reflect involved).
//   3. Consumers WITHOUT a generated companion degrade gracefully — the
//      framework returns sane fallbacks rather than crashing with
//      LinkageError / NoClassDefFoundError.
//
// If a future change accidentally re-introduces a `kotlin.reflect.full.*`
// call on the hot path, this suite is the proof that breaks the build.

@Generable("customer with a hand-written generated companion")
data class GeneratedCustomer(val id: String, val tier: Int)

// Hand-rolled stand-in for what `:agents-kt-ksp` emits. The runtime's
// GeneratedMetaCache loads this object via plain JDK reflection
// (Class.forName + java.lang.reflect.Method) — never via kotlin-reflect.
internal object GeneratedCustomer__GeneratedSchema {
    const val JSON_SCHEMA: String =
        """{"type":"object","properties":{"id":{"type":"string"},"tier":{"type":"integer"}},"required":["id","tier"],"additionalProperties":false}"""
    const val LLM_DESCRIPTION: String =
        "## GeneratedCustomer — picked up from generated companion"

    @JvmStatic
    fun constructFromMap(fields: Map<*, Any?>): GeneratedCustomer {
        return GeneratedCustomer(
            id = fields["id"] as? String ?: error("missing id"),
            tier = (fields["tier"] as? Number)?.toInt() ?: error("missing tier"),
        )
    }
}

// No __GeneratedSchema companion. With kotlin-reflect absent, every
// reflective path the runtime might walk here must degrade cleanly.
@Generable("customer without any generated companion")
data class ReflectOnlyCustomer(val email: String)

class NoReflectSmokeTest {

    @Test
    fun `kotlin-reflect is provably absent from the classpath`() {
        // The whole subproject's reason to exist: if any other assertion
        // in this file passes only because reflect leaked in, this guard
        // would have failed first.
        try {
            Class.forName("kotlin.reflect.full.KClasses")
            fail("kotlin-reflect IS on the classpath — the exclude in build.gradle.kts failed.")
        } catch (_: ClassNotFoundException) {
            // expected
        }
    }

    @Test
    fun `jsonSchema reads the generated companion when present`() {
        val schema = GeneratedCustomer::class.jsonSchema()
        assertTrue("\"id\"" in schema, "expected 'id' field in schema; got: $schema")
        assertTrue("\"tier\"" in schema, "expected 'tier' field in schema; got: $schema")
        assertTrue("additionalProperties" in schema, "expected strict schema; got: $schema")
    }

    @Test
    fun `jsonSchema returns the empty-object stub when no companion and no reflect`() {
        // Cache miss + LinkageError on the reflection branch ⇒ the
        // ReflectionFallback wrapper returns null ⇒ jsonSchema returns
        // the universal empty-object stub. Consumers see the same
        // schema they'd see for an unsupported type.
        assertEquals(
            """{"type":"object","additionalProperties":false}""",
            ReflectOnlyCustomer::class.jsonSchema(),
        )
    }

    @Test
    fun `toLlmDescription reads the generated constant when present`() {
        assertEquals(
            "## GeneratedCustomer — picked up from generated companion",
            GeneratedCustomer::class.toLlmDescription(),
        )
    }

    @Test
    fun `toLlmDescription falls back to the simple-name marker when no companion and no reflect`() {
        // Same cache-miss + LinkageError path, different terminal fallback.
        assertEquals("## ReflectOnlyCustomer", ReflectOnlyCustomer::class.toLlmDescription())
    }

    @Test
    fun `fromLlmOutput uses the generated constructFromMap when present`() {
        val parsed = GeneratedCustomer::class.fromLlmOutput("""{"id":"abc","tier":3}""")
        assertNotNull(parsed, "expected the generated constructor path to succeed")
        assertEquals("abc", parsed.id)
        assertEquals(3, parsed.tier)
    }

    @Test
    fun `fromLlmOutput returns null when there is no companion and no reflect`() {
        // Cache miss + reflection unavailable ⇒ constructFromMap returns
        // null ⇒ fromLlmOutput propagates null. The agentic loop's
        // onError.invalidArgs path is what consumers wire downstream.
        assertNull(ReflectOnlyCustomer::class.fromLlmOutput("""{"email":"x@y"}"""))
    }
}
