package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for the `private object GeneratedMetaCache` inside GenerableSupport.kt.
// 14 mutants (13 SURVIVED + 1 NO_COVERAGE) cluster on `load` and `tryLoad` —
// the cache-and-load helper that finds `${fqn}__GeneratedSchema` companions
// emitted by the KSP processor. The cache is `private`; we exercise it via
// the public lookup surface (jsonSchema / toLlmDescription / constructFromMap)
// on hand-crafted fixtures with deliberately-shaped __GeneratedSchema classes.
//
// Each fixture is a pair (Target class + sibling __GeneratedSchema object)
// where the object's static final String fields drive what GeneratedMetaCache
// is supposed to find or filter out.
class GeneratedMetaCacheTest {

    // ── happy-path: clean __GeneratedSchema fills both lookups + ctor ─────────

    @Test
    fun `lookupJsonSchema returns generated JSON_SCHEMA verbatim`() {
        // Kills the cache-hit / tryLoad-return-Entry chain: the assertion ties
        // the public return value to a string that ONLY the generated companion
        // carries (the reflection path would emit a different schema shape).
        val schema = MetaCacheCleanFoo::class.jsonSchema()
        assertEquals(GENERATED_CLEAN_FOO_SCHEMA, schema,
            "generated JSON_SCHEMA must be returned byte-identical, not reflection-built: '$schema'")
    }

    @Test
    fun `toLlmDescription returns generated LLM_DESCRIPTION verbatim`() {
        val desc = MetaCacheCleanFoo::class.toLlmDescription()
        assertEquals(GENERATED_CLEAN_FOO_DESC, desc,
            "generated LLM_DESCRIPTION must be returned verbatim: '$desc'")
    }

    @Test
    fun `constructFromMap dispatches to generated constructor when present`() {
        // The fixture's constructFromMap sets a sentinel flag we read back to
        // prove the GENERATED path executed (vs reflection-fallback).
        MetaCacheCleanFoo__GeneratedSchema.lastCtorInvoked = false
        val out = MetaCacheCleanFoo::class.constructFromMap(mapOf("dummy" to 1))
        assertNotNull(out)
        assertTrue(MetaCacheCleanFoo__GeneratedSchema.lastCtorInvoked,
            "generated constructFromMap must have run (sentinel flag)")
    }

    // ── cache: second call returns same value (no re-parse) ───────────────────

    @Test
    fun `lookupJsonSchema cache returns same value on repeat call`() {
        // Kills the L 69 cache-hit branch indirectly: if the cache check were
        // negated, the second call would re-walk the fields, but still return
        // the same content (so equality alone doesn't kill it). We assert
        // referential equality on the returned String — generated const-vals
        // come back as the SAME String instance via field.get(null), so the
        // intern() / cache-hit path yields `===` equality across calls.
        val a = MetaCacheCleanFoo::class.jsonSchema()
        val b = MetaCacheCleanFoo::class.jsonSchema()
        assertTrue(a === b, "repeat lookup must return the same String instance (cache hit + const pool)")
    }

    // ── empty fixture: __GeneratedSchema exists but has nothing useful ────────

    @Test
    fun `lookup falls through to reflection when __GeneratedSchema has no constants and no constructor`() {
        // Kills L 103 `if (constants.isEmpty() && constructor == null) null`:
        // both empty → tryLoad returns null → load caches MISS → public
        // lookup falls through to reflection.
        val schema = MetaCacheEmptyBar::class.jsonSchema()
        // Reflection-built schema includes "type":"object" with declared field name.
        assertTrue(schema.contains(""""type":"object""""),
            "empty generated schema must fall through to reflection: $schema")
        assertTrue(schema.contains("greeting"),
            "reflection schema must include the actual field 'greeting': $schema")
    }

    // ── type filter: non-String JSON_SCHEMA is skipped ────────────────────────

    @Test
    fun `non-String fields are filtered out of the constants map`() {
        // Kills L 88 `field.type == String::class.java` negation: the fixture
        // has `const val JSON_SCHEMA: Int = 42` — a static final NON-String.
        // The type guard must reject it; jsonSchema() should NOT return "42"
        // and must fall through to the reflection path.
        val schema = MetaCacheBadTypeBaz::class.jsonSchema()
        assertFalse(schema.contains("42"),
            "Int-typed JSON_SCHEMA must be filtered by the type guard: $schema")
        // LLM_DESCRIPTION (a real String const) IS still loaded.
        assertEquals(GENERATED_BAD_TYPE_DESC, MetaCacheBadTypeBaz::class.toLlmDescription(),
            "co-located String const must still load even when sibling Int is filtered")
    }

    // ── final filter: @JvmField var (non-final) is skipped ────────────────────

    @Test
    fun `non-final static String fields are filtered out`() {
        // Kills L 87 `Modifier.isFinal(mods)` negation: @JvmField var produces
        // a static-but-NOT-final field. The final-guard must reject it.
        val schema = MetaCacheMutableQux::class.jsonSchema()
        assertFalse(schema.contains("should-be-filtered"),
            "non-final @JvmField var must be filtered: $schema")
        // The const val LLM_DESCRIPTION (static final String) still loads.
        assertEquals(GENERATED_MUTABLE_QUX_DESC, MetaCacheMutableQux::class.toLlmDescription())
    }

    // ── null-value filter: @JvmField val: String? = null is skipped ──────────

    @Test
    fun `static final String fields with null value are skipped from constants map`() {
        // Kills L 91/92 `if (value != null) constants[field.name] = value`:
        // a String? field whose value is null must NOT pollute the constants
        // map. The lookup falls through to reflection.
        val schema = MetaCacheNullValueQuux::class.jsonSchema()
        // If the null-value guard were broken, constants["JSON_SCHEMA"] would
        // be set to "" or NPE on the cast; either way the assertion below
        // would fail vs the reflection schema.
        assertTrue(schema.contains(""""type":"object""""),
            "null-valued JSON_SCHEMA must be ignored; reflection schema returned: $schema")
    }

    // ── static filter: instance (non-static) fields are skipped ───────────────

    @Test
    fun `instance (non-static) fields are filtered out by the static guard`() {
        // Kills L 86 `Modifier.isStatic(mods)` negation. The fixture's
        // __GeneratedSchema is a Kotlin `class` (not object), so its primary
        // ctor parameter becomes a non-static instance field. Class.forName
        // succeeds but the field is filtered, leaving constants empty +
        // constructor null → MISS cached.
        val schema = MetaCacheInstanceFields::class.jsonSchema()
        assertFalse(schema.contains("should-be-filtered"),
            "non-static instance field must be filtered: $schema")
        assertTrue(schema.contains(""""type":"object""""),
            "expected reflection fallback after filter rejected everything: $schema")
    }

    // ── constructor-only fixture: kills the L 103 constructor.isEmpty side ────

    @Test
    fun `__GeneratedSchema with only constructFromMap (no constants) still loads via Entry`() {
        // Kills L 103: `constants.isEmpty() && constructor == null`. With
        // constants empty AND constructor present, the `&&` short-circuits
        // to false → Entry returned (not null) → MISS not cached.
        // Verify by constructing a value through the public API.
        MetaCacheCtorOnly__GeneratedSchema.lastCtorInvoked = false
        val out = MetaCacheCtorOnly::class.constructFromMap(emptyMap<String, Any?>())
        assertNotNull(out, "constructor-only fixture must produce an instance")
        assertTrue(MetaCacheCtorOnly__GeneratedSchema.lastCtorInvoked,
            "generated constructFromMap must have run")
        // ALSO: jsonSchema lookup falls through to reflection because constants empty.
        val schema = MetaCacheCtorOnly::class.jsonSchema()
        assertTrue(schema.contains(""""type":"object""""),
            "no JSON_SCHEMA const → fall through to reflection: $schema")
    }

    // ── miss: class without any __GeneratedSchema at all ─────────────────────

    @Test
    fun `class without __GeneratedSchema falls through to reflection (ClassNotFoundException path)`() {
        // Kills the `catch (_: ClassNotFoundException)` early-return + the
        // L 70 `?: MISS` fallback. MetaCacheNoCompanion has NO generated
        // sibling, so Class.forName throws and tryLoad returns null.
        val desc = MetaCacheNoCompanion::class.toLlmDescription()
        // Reflection-built description includes the class name.
        assertTrue(desc.contains("MetaCacheNoCompanion"),
            "reflection fallback must run when no __GeneratedSchema exists: $desc")
        // Repeat call exercises the cached-MISS path (L 69 hit).
        val again = MetaCacheNoCompanion::class.toLlmDescription()
        assertEquals(desc, again, "cached MISS must yield the same reflection result")
    }

    // ── distinct return: generated and reflection paths differ ───────────────

    @Test
    fun `generated JSON_SCHEMA differs from reflection-built schema (proves cache short-circuits)`() {
        // Defensive: if the cache return were skipped entirely, the public
        // method would still produce a valid (reflection) schema — but a
        // DIFFERENT one. Comparing them proves the cache fired.
        val generated = MetaCacheCleanFoo::class.jsonSchema()
        // The reflection path on the same class would compute something
        // structurally different — `{"type":"object","properties":{...}}`
        // — not our hand-crafted GENERATED_CLEAN_FOO_SCHEMA sentinel.
        assertNotEquals("""{"type":"object","properties":{},"additionalProperties":false}""", generated,
            "generated path must not collapse to the trivial empty-object schema")
        assertEquals(GENERATED_CLEAN_FOO_SCHEMA, generated)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Fixtures — top-level so their FQNs are predictable for the
// `${fqn}__GeneratedSchema` lookup convention.
// ─────────────────────────────────────────────────────────────────────────────

// Sentinel constants used in assertions, defined once.
internal const val GENERATED_CLEAN_FOO_SCHEMA = """{"type":"object","properties":{"clean":{"type":"boolean"}}}"""
internal const val GENERATED_CLEAN_FOO_DESC = "## CleanFoo (generated)"
internal const val GENERATED_BAD_TYPE_DESC = "## BadType (generated, mixed-type fields)"
internal const val GENERATED_MUTABLE_QUX_DESC = "## MutableQux (generated, non-final sibling)"

// FIXTURE 1: clean — both constants + a constructor.
class MetaCacheCleanFoo
object MetaCacheCleanFoo__GeneratedSchema {
    const val JSON_SCHEMA: String = GENERATED_CLEAN_FOO_SCHEMA
    const val LLM_DESCRIPTION: String = GENERATED_CLEAN_FOO_DESC

    // Sentinel so the test can verify the GENERATED ctor ran (vs reflection).
    @JvmField var lastCtorInvoked: Boolean = false

    @JvmStatic
    fun constructFromMap(@Suppress("UNUSED_PARAMETER") fields: Map<*, Any?>): MetaCacheCleanFoo {
        lastCtorInvoked = true
        return MetaCacheCleanFoo()
    }
}

// FIXTURE 2: empty — companion exists but has no string consts and no ctor.
data class MetaCacheEmptyBar(val greeting: String = "hi")
object MetaCacheEmptyBar__GeneratedSchema {
    // Intentionally empty. INSTANCE field is static final but type ≠ String.
}

// FIXTURE 3: non-String const → must be filtered by the type guard.
class MetaCacheBadTypeBaz
object MetaCacheBadTypeBaz__GeneratedSchema {
    const val JSON_SCHEMA: Int = 42  // wrong type — filtered
    const val LLM_DESCRIPTION: String = GENERATED_BAD_TYPE_DESC
}

// FIXTURE 4: non-final var → must be filtered by the final guard.
class MetaCacheMutableQux
object MetaCacheMutableQux__GeneratedSchema {
    @JvmField var JSON_SCHEMA: String = "should-be-filtered"  // non-final
    const val LLM_DESCRIPTION: String = GENERATED_MUTABLE_QUX_DESC
}

// FIXTURE 5: static final String? with null value → skipped by value-null guard.
data class MetaCacheNullValueQuux(val payload: String = "p")
object MetaCacheNullValueQuux__GeneratedSchema {
    @JvmField val JSON_SCHEMA: String? = null  // static final String, value=null
}

// FIXTURE 6: non-static instance fields → filtered by the static guard.
// Using a Kotlin `class` (not object) for the __GeneratedSchema means the
// primary-constructor backed field is an INSTANCE field, not static.
data class MetaCacheInstanceFields(val text: String = "default")
@Suppress("unused")
class MetaCacheInstanceFields__GeneratedSchema {
    @JvmField val JSON_SCHEMA: String = "should-be-filtered"  // instance field, not static
}

// FIXTURE 7: constructor only, no string consts.
class MetaCacheCtorOnly
object MetaCacheCtorOnly__GeneratedSchema {
    @JvmField var lastCtorInvoked: Boolean = false

    @JvmStatic
    fun constructFromMap(@Suppress("UNUSED_PARAMETER") fields: Map<*, Any?>): MetaCacheCtorOnly {
        lastCtorInvoked = true
        return MetaCacheCtorOnly()
    }
}

// FIXTURE 8: no __GeneratedSchema sibling at all → ClassNotFoundException path.
data class MetaCacheNoCompanion(val n: Int = 0)
// (intentionally NO MetaCacheNoCompanion__GeneratedSchema)
