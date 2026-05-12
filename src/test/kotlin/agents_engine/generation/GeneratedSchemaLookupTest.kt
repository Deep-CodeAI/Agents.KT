package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Tests for #1701 — the runtime's `KClass.jsonSchema()` looks up a
// KSP-generated `<ClassName>__GeneratedSchema.JSON_SCHEMA` constant and
// returns it instead of walking the class via reflection. Real KSP-emitted
// files are produced by the `:agents-kt-ksp` processor; for this test we
// hand-write a sample alongside its `@Generable` to prove the lookup path
// works end-to-end without needing the KSP build step.

// ── Class with a hand-written generated companion ────────────────────────────

@Generable("user shape with a generated schema")
data class HandGeneratedUser(val name: String, val age: Int)

// Mimics what `:agents-kt-ksp` emits per data class. `internal` visibility
// matches the generated code so the lookup mechanism is exercised exactly
// as it would be in production.
internal object HandGeneratedUser__GeneratedSchema {
    const val JSON_SCHEMA: String = """{"sentinel":"hand-generated-from-test","not":"the reflection output"}"""
}

// ── Class with NO generated companion (should fall back to reflection) ───────

@Generable("user with no generated companion")
data class ReflectionOnlyUser(val email: String)

// ── Sealed root with hand-written generated companion (#1702) ────────────────
//
// Demonstrates that the runtime lookup mechanism is shape-agnostic — sealed
// parents whose generated schema is the `{"oneOf":[...]}` shape resolve the
// same way as data-class shapes do.

@Generable("hand-generated sealed root")
sealed interface HandGeneratedDecision {
    @Generable("approved variant")
    data class Approved(val confidence: Double) : HandGeneratedDecision
    @Generable("rejected variant")
    data class Rejected(val reason: String) : HandGeneratedDecision
}

internal object HandGeneratedDecision__GeneratedSchema {
    const val JSON_SCHEMA: String = """{"sentinel":"hand-generated-sealed","wraps":"oneOf normally"}"""
}

// Sealed root WITHOUT a generated companion — must fall back to the runtime's
// sealedJsonSchema reflection path.

@Generable("reflection-only sealed")
sealed interface ReflectionOnlyDecision {
    @Generable data class Yes(val ok: Boolean) : ReflectionOnlyDecision
    @Generable data class No(val why: String) : ReflectionOnlyDecision
}

class GeneratedSchemaLookupTest {

    @Test
    fun `jsonSchema returns the generated constant when a __GeneratedSchema object exists`() {
        val schema = HandGeneratedUser::class.jsonSchema()
        assertEquals(
            """{"sentinel":"hand-generated-from-test","not":"the reflection output"}""",
            schema,
            "expected the generated companion's JSON_SCHEMA constant; got: $schema",
        )
    }

    @Test
    fun `jsonSchema falls back to reflection when no generated companion exists`() {
        val schema = ReflectionOnlyUser::class.jsonSchema()
        assertTrue("\"type\":\"object\"" in schema, "expected reflected schema; got: $schema")
        assertTrue("\"email\"" in schema, "expected the field name in reflected output; got: $schema")
        assertTrue("sentinel" !in schema, "must not have leaked the hand-generated sentinel; got: $schema")
    }

    @Test
    fun `lookup is cached — repeated calls return the same string (identity-stable per JVM)`() {
        val a = HandGeneratedUser::class.jsonSchema()
        val b = HandGeneratedUser::class.jsonSchema()
        // String identity isn't a strict contract (the cache could store
        // copies in some impls) but content-equality must hold.
        assertEquals(a, b)
    }

    @Test
    fun `the cached-miss path doesn't accidentally start returning the reflection output as the cache hit`() {
        // Two calls in a row against the no-companion class — second call
        // must come from the same reflection path as the first (no
        // accidental promotion to "schema hit").
        val a = ReflectionOnlyUser::class.jsonSchema()
        val b = ReflectionOnlyUser::class.jsonSchema()
        assertEquals(a, b)
        assertTrue("sentinel" !in a, "first call must be reflection: $a")
        assertTrue("sentinel" !in b, "cached call must still be reflection: $b")
    }

    // ── Sealed-root lookup (#1702) ───────────────────────────────────────────

    @Test
    fun `jsonSchema returns the generated constant for a sealed parent when companion exists`() {
        val schema = HandGeneratedDecision::class.jsonSchema()
        assertEquals(
            """{"sentinel":"hand-generated-sealed","wraps":"oneOf normally"}""",
            schema,
            "sealed parents should hit the generated lookup just like data classes",
        )
    }

    @Test
    fun `jsonSchema falls back to sealedJsonSchema reflection for a sealed parent with no companion`() {
        val schema = ReflectionOnlyDecision::class.jsonSchema()
        assertTrue("\"oneOf\"" in schema, "expected oneOf wrapper from reflection: $schema")
        assertTrue("Yes" in schema && "No" in schema, "expected both variants enumerated: $schema")
        assertTrue("sentinel" !in schema, "must not pick up the hand-generated sentinel from the other test: $schema")
    }
}
