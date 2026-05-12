package agents_engine.ksp

import agents_engine.ksp.GenerableValidator.Field
import agents_engine.ksp.GenerableValidator.FieldType
import agents_engine.ksp.GenerableValidator.GenerableClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the constructFromMap codegen (#1704). Pins the generated
 * Kotlin source byte-for-byte against today's design — when the runtime
 * coercion contract changes, the emitter must change in lockstep and
 * these tests catch the drift.
 */
class ConstructFromMapEmitterTest {

    private fun cls(
        name: String,
        fields: List<Field>,
        sealed: Boolean = false,
        variants: List<GenerableClass> = emptyList(),
    ) = GenerableClass(
        qualifiedName = "com.example.$name",
        isSealed = sealed,
        isAbstract = false,
        isInterface = false,
        isEnum = false,
        isAnnotation = false,
        hasPrimaryConstructor = !sealed,
        primaryConstructorParamCount = fields.size,
        fields = fields,
        simpleName = name,
        sealedVariants = variants,
    )

    private fun field(
        name: String,
        type: FieldType,
        nullable: Boolean = false,
        hasDefault: Boolean = false,
    ) = Field(name, type, nullable, hasDefault, guideDescription = null)

    @Test
    fun `canGenerate — simple data class with primitive required fields, eligible`() {
        val target = cls("Person", listOf(
            field("name", FieldType.StringT),
            field("age", FieldType.IntT),
        ))
        assertTrue(ConstructFromMapEmitter.canGenerate(target))
    }

    @Test
    fun `canGenerate — class with any default-valued param, skipped`() {
        val target = cls("Greet", listOf(
            field("name", FieldType.StringT),
            field("language", FieldType.StringT, hasDefault = true),
        ))
        assertFalse(
            ConstructFromMapEmitter.canGenerate(target),
            "default-valued params can't be invoked without the synthetic mask ctor; reflection path takes over",
        )
    }

    @Test
    fun `canGenerate — class with no fields, skipped (nothing to deserialize)`() {
        val target = cls("Empty", emptyList())
        assertFalse(ConstructFromMapEmitter.canGenerate(target))
    }

    @Test
    fun `canGenerate — sealed root always eligible (no fields of its own)`() {
        val target = cls("Decision", emptyList(), sealed = true, variants = listOf(
            cls("Approved", listOf(field("c", FieldType.DoubleT))),
        ))
        assertTrue(ConstructFromMapEmitter.canGenerate(target))
    }

    @Test
    fun `data class — simple Person source pins exactly`() {
        val out = ConstructFromMapEmitter.emitDataClassBody(
            cls("Person", listOf(
                field("name", FieldType.StringT),
                field("age", FieldType.IntT),
            )),
            isSealedVariant = false,
        )
        assertEquals(
            """
        val allowed = setOf("name", "age")
        for (k in fields.keys) {
            val kStr = k?.toString() ?: continue
            if (kStr !in allowed) return null
        }
        val name = agents_engine.generation.coerceString(fields["name"]) ?: return null
        val age = agents_engine.generation.coerceInt(fields["age"]) ?: return null
        return try {
            com.example.Person(
                name = name,
                age = age
            )
        } catch (_: Exception) { null }
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `data class — nullable field binds null directly, doesn't short-circuit`() {
        val out = ConstructFromMapEmitter.emitDataClassBody(
            cls("User", listOf(
                field("name", FieldType.StringT),
                field("nickname", FieldType.StringT, nullable = true),
            )),
            isSealedVariant = false,
        )
        // The "?: return null" must be on `name` (required) but NOT on
        // `nickname` (nullable). Verify directly.
        assertTrue(
            "val name = agents_engine.generation.coerceString(fields[\"name\"]) ?: return null" in out,
            "required field should short-circuit on coercion miss: $out",
        )
        assertTrue(
            "val nickname = agents_engine.generation.coerceString(fields[\"nickname\"])\n" in out,
            "nullable field must not short-circuit (bind null directly): $out",
        )
        assertFalse(
            "val nickname = agents_engine.generation.coerceString(fields[\"nickname\"]) ?: return null" in out,
            "nullable field must not have ?: return null clause: $out",
        )
    }

    @Test
    fun `data class — list field uses coerceList with per-item lambda`() {
        val out = ConstructFromMapEmitter.emitDataClassBody(
            cls("Tags", listOf(field("tags", FieldType.ListT(FieldType.StringT)))),
            isSealedVariant = false,
        )
        assertTrue(
            "agents_engine.generation.coerceList(fields[\"tags\"]) { agents_engine.generation.coerceString(it) }" in out,
            "expected coerceList with per-item String coercion: $out",
        )
    }

    @Test
    fun `data class — nested @Generable ref dispatches to the nested generated companion`() {
        val out = ConstructFromMapEmitter.emitDataClassBody(
            cls("Order", listOf(field("customer", FieldType.GenerableRef("com.example.Customer")))),
            isSealedVariant = false,
        )
        assertTrue(
            "(fields[\"customer\"] as? Map<*, *>)?.let { com.example.Customer__GeneratedSchema.constructFromMap(it) }" in out,
            "expected dispatch to nested companion: $out",
        )
    }

    @Test
    fun `sealed variant — emits discriminator check + allows "type" as extra key`() {
        val out = ConstructFromMapEmitter.emitDataClassBody(
            cls("Approved", listOf(field("confidence", FieldType.DoubleT))),
            isSealedVariant = true,
        )
        // The allowed-keys set must contain "type".
        assertTrue("""setOf("confidence", "type")""" in out, "type key must be allowed for sealed variant: $out")
        // Discriminator check: if "type" present, it must equal this variant's name.
        assertTrue(
            """if (discriminator != null && discriminator != "Approved") return null""" in out,
            "expected discriminator check pinned to 'Approved': $out",
        )
    }

    @Test
    fun `sealed root — dispatches by type name to each variant's generated companion`() {
        val out = ConstructFromMapEmitter.emitSealedDispatchBody(
            cls("Decision", emptyList(), sealed = true, variants = listOf(
                cls("Approved", listOf(field("c", FieldType.DoubleT))),
                cls("Rejected", listOf(field("r", FieldType.StringT))),
            )),
        )
        assertEquals(
            """
        val typeName = fields["type"] as? String ?: return null
        return when (typeName) {
            "Approved" -> com.example.Approved__GeneratedSchema.constructFromMap(fields)
            "Rejected" -> com.example.Rejected__GeneratedSchema.constructFromMap(fields)
            else -> null
        }
            """.trimIndent(),
            out,
        )
    }
}
