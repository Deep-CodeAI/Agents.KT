package agents_engine.ksp

import agents_engine.ksp.GenerableValidator.Field
import agents_engine.ksp.GenerableValidator.FieldType
import agents_engine.ksp.GenerableValidator.GenerableClass
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the LLM-description emitter (#1703).
 *
 * Each test pins one markdown-shape concern. The strings below are what
 * the runtime path produces today (`GenerableSupport.dataClassLlmDescription`
 * / `sealedLlmDescription`) — **byte-identical** is the contract.
 */
class LlmDescriptionEmitterTest {

    private fun dataClass(
        name: String,
        fields: List<Field> = emptyList(),
        generableDescription: String = "",
        llmOverride: String? = null,
    ) = GenerableClass(
        qualifiedName = "com.example.$name",
        isSealed = false,
        isAbstract = false,
        isInterface = false,
        isEnum = false,
        isAnnotation = false,
        hasPrimaryConstructor = true,
        primaryConstructorParamCount = fields.size,
        fields = fields,
        simpleName = name,
        generableDescription = generableDescription,
        llmDescriptionOverride = llmOverride,
    )

    private fun variant(name: String, fields: List<Field>, guide: String? = null) = GenerableClass(
        qualifiedName = "com.example.$name",
        isSealed = false,
        isAbstract = false,
        isInterface = false,
        isEnum = false,
        isAnnotation = false,
        hasPrimaryConstructor = true,
        primaryConstructorParamCount = fields.size,
        fields = fields,
        simpleName = name,
        guideDescription = guide,
    )

    private fun sealedRoot(
        name: String,
        variants: List<GenerableClass>,
        generableDescription: String = "",
        llmOverride: String? = null,
    ) = GenerableClass(
        qualifiedName = "com.example.$name",
        isSealed = true,
        isAbstract = true,
        isInterface = false,
        isEnum = false,
        isAnnotation = false,
        hasPrimaryConstructor = false,
        primaryConstructorParamCount = 0,
        simpleName = name,
        sealedVariants = variants,
        generableDescription = generableDescription,
        llmDescriptionOverride = llmOverride,
    )

    private fun field(name: String, type: FieldType, guide: String? = null) =
        Field(name, type, isNullable = false, hasDefault = false, guideDescription = guide)

    @Test
    fun `data class — header plus generable description plus field bullets`() {
        val out = LlmDescriptionEmitter.emitDataClass(dataClass(
            "Person",
            fields = listOf(
                field("name", FieldType.StringT),
                field("age", FieldType.IntT, guide = "how old"),
            ),
            generableDescription = "a person",
        ))
        assertEquals(
            """
                ## Person

                a person

                - **name** (String)
                - **age** (Int): how old
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `data class — no generable description means no intro paragraph`() {
        val out = LlmDescriptionEmitter.emitDataClass(dataClass(
            "Order",
            fields = listOf(field("id", FieldType.IntT)),
        ))
        assertEquals(
            """
                ## Order

                - **id** (Int)
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `data class — no fields produces header only, no bullets`() {
        val out = LlmDescriptionEmitter.emitDataClass(dataClass(
            "Empty",
            generableDescription = "nothing inside",
        ))
        assertEquals(
            """
                ## Empty

                nothing inside
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `data class — promptTypeName covers each supported field type correctly`() {
        val out = LlmDescriptionEmitter.emitDataClass(dataClass(
            "AllPrims",
            fields = listOf(
                field("s", FieldType.StringT),
                field("i", FieldType.IntT),
                field("l", FieldType.LongT),
                field("d", FieldType.DoubleT),
                field("f", FieldType.FloatT),
                field("b", FieldType.BoolT),
                field("xs", FieldType.ListT(FieldType.StringT)),
                field("inner", FieldType.GenerableRef("com.example.Other")),
            ),
        ))
        assertEquals(
            """
                ## AllPrims

                - **s** (String)
                - **i** (Int)
                - **l** (Long)
                - **d** (Double)
                - **f** (Float)
                - **b** (Boolean)
                - **xs** (List<String>)
                - **inner** (Other)
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `data class — @LlmDescription override wins, returned verbatim`() {
        val out = LlmDescriptionEmitter.emitDataClass(dataClass(
            "WithOverride",
            fields = listOf(field("ignored", FieldType.StringT, guide = "this would normally render")),
            generableDescription = "ignored too",
            llmOverride = "Custom prompt text\nwith newlines and **markdown** that should stay verbatim.",
        ))
        assertEquals(
            "Custom prompt text\nwith newlines and **markdown** that should stay verbatim.",
            out,
        )
    }

    @Test
    fun `sealed — two variants with @Guide on one, params under each`() {
        val out = LlmDescriptionEmitter.emitSealed(sealedRoot(
            "Decision",
            variants = listOf(
                variant("Approved", listOf(field("confidence", FieldType.DoubleT))),
                variant(
                    "Rejected",
                    listOf(field("reason", FieldType.StringT)),
                    guide = "rejection reason",
                ),
            ),
            generableDescription = "a decision",
        ))
        assertEquals(
            """
                ## Decision

                a decision

                Choose one of the following variants:

                ### Approved
                - **confidence** (Double)

                ### Rejected: rejection reason
                - **reason** (String)
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `sealed — no generable description, no variant guides`() {
        val out = LlmDescriptionEmitter.emitSealed(sealedRoot(
            "Status",
            variants = listOf(
                variant("On", emptyList()),
                variant("Off", emptyList()),
            ),
        ))
        assertEquals(
            """
                ## Status

                Choose one of the following variants:

                ### On

                ### Off
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `sealed — empty variants list — header plus prompt only`() {
        val out = LlmDescriptionEmitter.emitSealed(sealedRoot("Empty", emptyList()))
        assertEquals(
            """
                ## Empty

                Choose one of the following variants:
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `sealed — @LlmDescription override wins, returned verbatim`() {
        val out = LlmDescriptionEmitter.emitSealed(sealedRoot(
            "Decision",
            variants = listOf(variant("Approved", emptyList())),
            llmOverride = "Override sealed text",
        ))
        assertEquals("Override sealed text", out)
    }
}
