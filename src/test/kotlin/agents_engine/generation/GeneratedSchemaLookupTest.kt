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
    // #1703: LLM_DESCRIPTION shipped in the same generated object.
    const val LLM_DESCRIPTION: String = "HAND-WRITTEN LLM DESC — not the reflected markdown"
    // #1704: constructFromMap method. The runtime cache finds it via JDK
    // reflection (java.lang.reflect, NOT kotlin-reflect) and invokes it.
    // Returns a sentinel so the test can distinguish "generated path"
    // from "reflection path".
    @JvmStatic
    fun constructFromMap(fields: Map<*, Any?>): HandGeneratedUser {
        return HandGeneratedUser(
            name = "GENERATED:${fields["name"]}",
            age = (fields["age"] as? Number)?.toInt() ?: 0,
        )
    }
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

    // ── LLM description lookup (#1703) ───────────────────────────────────────

    @Test
    fun `toLlmDescription returns the LLM_DESCRIPTION constant when a generated companion exists`() {
        val md = HandGeneratedUser::class.toLlmDescription()
        assertEquals(
            "HAND-WRITTEN LLM DESC — not the reflected markdown",
            md,
            "expected the generated LLM_DESCRIPTION; got: $md",
        )
    }

    @Test
    fun `toLlmDescription falls back to reflection for a class with no generated companion`() {
        val md = ReflectionOnlyUser::class.toLlmDescription()
        assertTrue("## ReflectionOnlyUser" in md, "expected reflected header: $md")
        assertTrue("- **email**" in md, "expected reflected field bullet: $md")
        assertTrue("HAND-WRITTEN" !in md, "must not leak the sentinel from another test: $md")
    }

    @Test
    fun `cache loads multiple constants in one Class-dot-forName attempt`() {
        // Touching either jsonSchema or toLlmDescription should populate the
        // cache for both. Second access path is then "already loaded" — same
        // string returned, same identity expected for cached const.
        val schemaFirst = HandGeneratedUser::class.jsonSchema()
        val descSecond = HandGeneratedUser::class.toLlmDescription()
        assertTrue(schemaFirst.startsWith("{"), "schema should still be JSON: $schemaFirst")
        assertTrue(descSecond.startsWith("HAND-WRITTEN"), "desc should still be the const: $descSecond")
    }

    // ── constructFromMap lookup (#1704) ──────────────────────────────────────

    @Test
    fun `fromLlmOutput uses the generated constructFromMap when present`() {
        // The "GENERATED:" prefix on the resulting name proves the
        // generated companion was used, NOT the reflection ctor.
        val out = HandGeneratedUser::class.fromLlmOutput("""{"name":"alice","age":30}""")
        assertEquals(
            HandGeneratedUser(name = "GENERATED:alice", age = 30),
            out,
            "expected the generated constructFromMap path; got: $out",
        )
    }

    @Test
    fun `fromLlmOutput falls back to reflection when no generated constructor exists`() {
        // ReflectionOnlyUser has no __GeneratedSchema companion → cache miss
        // → existing reflection path constructs via primaryConstructor.callBy.
        val out = ReflectionOnlyUser::class.fromLlmOutput("""{"email":"alice@example.com"}""")
        assertEquals(ReflectionOnlyUser(email = "alice@example.com"), out)
    }
}
