package agents_engine.ksp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the validation rules behind #1700.
 *
 * Tests the rules directly via the pure-data `GenerableClass` model. The
 * KSP adapter that builds that model from `KSClassDeclaration` is exercised
 * by integration of the full build — these tests pin the rule semantics.
 */
class GenerableValidatorTest {

    private fun cls(
        name: String = "com.example.Person",
        sealed: Boolean = false,
        abstract: Boolean = false,
        iface: Boolean = false,
        enum: Boolean = false,
        annotation: Boolean = false,
        hasPrimaryCtor: Boolean = true,
        paramCount: Int = 2,
    ) = GenerableValidator.GenerableClass(
        qualifiedName = name,
        isSealed = sealed,
        isAbstract = abstract,
        isInterface = iface,
        isEnum = enum,
        isAnnotation = annotation,
        hasPrimaryConstructor = hasPrimaryCtor,
        primaryConstructorParamCount = paramCount,
    )

    @Test
    fun `good — data class with two params produces no errors`() {
        val errors = GenerableValidator.validate(cls())
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `good — sealed class is accepted as a polymorphic root (validates via 'type' discriminator at runtime)`() {
        val errors = GenerableValidator.validate(cls(sealed = true, paramCount = 0, hasPrimaryCtor = false))
        assertEquals(emptyList(), errors, "sealed types are valid @Generable roots; variants validate separately")
    }

    @Test
    fun `good — sealed interface is accepted as a polymorphic root`() {
        // The exact pattern Decision937 / `sealed interface` uses in the codebase.
        val errors = GenerableValidator.validate(cls(sealed = true, iface = true, abstract = true, paramCount = 0, hasPrimaryCtor = false))
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `bad — non-sealed interface produces an error pointing at sealed or data class`() {
        val errors = GenerableValidator.validate(cls(iface = true, abstract = true, paramCount = 0, hasPrimaryCtor = false))
        assertEquals(1, errors.size, "expected exactly 1 (kind) error: $errors")
        assertTrue("interface" in errors[0].lowercase(), errors[0])
        assertTrue("sealed" in errors[0].lowercase() || "data class" in errors[0].lowercase(),
            "message should hint at the fix: $errors")
    }

    @Test
    fun `bad — enum class produces an error mentioning enum`() {
        val errors = GenerableValidator.validate(cls(enum = true, paramCount = 0, hasPrimaryCtor = false))
        assertTrue(errors.any { "enum" in it.lowercase() }, errors.toString())
        // Also catches the no-primary-ctor case; both fire.
    }

    @Test
    fun `bad — annotation class produces an error mentioning annotation`() {
        val errors = GenerableValidator.validate(cls(annotation = true, abstract = true, paramCount = 0))
        assertTrue(errors.any { "annotation" in it.lowercase() }, errors.toString())
    }

    @Test
    fun `bad — abstract class (non-interface, non-enum) produces an error mentioning abstract`() {
        val errors = GenerableValidator.validate(cls(abstract = true))
        assertTrue(errors.any { "abstract" in it.lowercase() }, errors.toString())
    }

    @Test
    fun `bad — no primary constructor produces an error`() {
        val errors = GenerableValidator.validate(cls(hasPrimaryCtor = false, paramCount = 0))
        assertTrue(errors.any { "primary constructor" in it.lowercase() }, errors.toString())
    }

    @Test
    fun `bad — primary constructor with zero params produces an error`() {
        val errors = GenerableValidator.validate(cls(paramCount = 0))
        assertEquals(1, errors.size, errors.toString())
        assertTrue("primary-constructor parameter" in errors[0].lowercase() ||
                   "at least one" in errors[0].lowercase(), errors[0])
    }

    @Test
    fun `single-rule policy — kind error suppresses the no-params error (most actionable diagnostic first)`() {
        // An enum is rejected on kind grounds; the secondary "no primary
        // constructor params" error would be confusing alongside it.
        val errors = GenerableValidator.validate(cls(enum = true, paramCount = 0, hasPrimaryCtor = false))
        assertEquals(1, errors.size, "expected only the kind-specific error: $errors")
        assertTrue("enum" in errors[0].lowercase(), errors[0])
    }

    @Test
    fun `messages identify the offending class by qualified name`() {
        val errors = GenerableValidator.validate(cls(name = "com.example.deep.Foo", abstract = true))
        assertTrue("com.example.deep.Foo" in errors[0], errors[0])
    }
}
