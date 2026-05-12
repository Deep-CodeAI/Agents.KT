package agents_engine.ksp

import agents_engine.ksp.GenerableValidator.Field
import agents_engine.ksp.GenerableValidator.FieldType
import agents_engine.ksp.GenerableValidator.GenerableClass
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the schema emitter (#1701).
 *
 * Each test pins one wire-shape concern. The strings below are what the
 * runtime path (`GenerableSupport.dataClassJsonSchema`) produces today —
 * **byte-identical** is the contract, so changes to either side must show
 * up as a failing test.
 */
class SchemaEmitterTest {

    private fun cls(
        name: String,
        fields: List<Field>,
    ) = GenerableClass(
        qualifiedName = name,
        isSealed = false,
        isAbstract = false,
        isInterface = false,
        isEnum = false,
        isAnnotation = false,
        hasPrimaryConstructor = true,
        primaryConstructorParamCount = fields.size,
        fields = fields,
    )

    private fun field(
        name: String,
        type: FieldType,
        nullable: Boolean = false,
        hasDefault: Boolean = false,
        guide: String? = null,
    ) = Field(name, type, nullable, hasDefault, guide)

    @Test
    fun `simple data class with primitive fields`() {
        val schema = SchemaEmitter.emitDataClassSchema(cls(
            "com.example.Person",
            listOf(
                field("name", FieldType.StringT),
                field("age", FieldType.IntT),
            ),
        ))
        assertEquals(
            """{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}},"required":["name","age"],"additionalProperties":false}""",
            schema,
        )
    }

    @Test
    fun `all primitive types map to the same JSON schema types as the runtime`() {
        val schema = SchemaEmitter.emitDataClassSchema(cls(
            "com.example.AllPrims",
            listOf(
                field("s", FieldType.StringT),
                field("i", FieldType.IntT),
                field("l", FieldType.LongT),
                field("d", FieldType.DoubleT),
                field("f", FieldType.FloatT),
                field("b", FieldType.BoolT),
            ),
        ))
        assertEquals(
            """{"type":"object","properties":{"s":{"type":"string"},"i":{"type":"integer"},"l":{"type":"integer"},"d":{"type":"number"},"f":{"type":"number"},"b":{"type":"boolean"}},"required":["s","i","l","d","f","b"],"additionalProperties":false}""",
            schema,
        )
    }

    @Test
    fun `nullable field is not in required list`() {
        val schema = SchemaEmitter.emitDataClassSchema(cls(
            "com.example.Optional",
            listOf(
                field("name", FieldType.StringT),
                field("nickname", FieldType.StringT, nullable = true),
            ),
        ))
        assertEquals(
            """{"type":"object","properties":{"name":{"type":"string"},"nickname":{"type":"string"}},"required":["name"],"additionalProperties":false}""",
            schema,
        )
    }

    @Test
    fun `field with default value is not in required list`() {
        val schema = SchemaEmitter.emitDataClassSchema(cls(
            "com.example.WithDefault",
            listOf(
                field("name", FieldType.StringT),
                field("greeting", FieldType.StringT, hasDefault = true),
            ),
        ))
        assertEquals(
            """{"type":"object","properties":{"name":{"type":"string"},"greeting":{"type":"string"}},"required":["name"],"additionalProperties":false}""",
            schema,
        )
    }

    @Test
    fun `@Guide description is embedded in the type object`() {
        val schema = SchemaEmitter.emitDataClassSchema(cls(
            "com.example.Guided",
            listOf(
                field("email", FieldType.StringT, guide = "RFC 5322 email address"),
            ),
        ))
        assertEquals(
            """{"type":"object","properties":{"email":{"type":"string","description":"RFC 5322 email address"}},"required":["email"],"additionalProperties":false}""",
            schema,
        )
    }

    @Test
    fun `@Guide with special chars is JSON-escaped`() {
        val schema = SchemaEmitter.emitDataClassSchema(cls(
            "com.example.E",
            listOf(field("x", FieldType.StringT, guide = "quotes \" and \\ and newline\n and tab\t")),
        ))
        assertEquals(
            """{"type":"object","properties":{"x":{"type":"string","description":"quotes \" and \\ and newline\n and tab\t"}},"required":["x"],"additionalProperties":false}""",
            schema,
        )
    }

    @Test
    fun `List of String produces array of strings`() {
        val schema = SchemaEmitter.emitDataClassSchema(cls(
            "com.example.Tags",
            listOf(field("tags", FieldType.ListT(FieldType.StringT))),
        ))
        assertEquals(
            """{"type":"object","properties":{"tags":{"type":"array","items":{"type":"string"}}},"required":["tags"],"additionalProperties":false}""",
            schema,
        )
    }

    @Test
    fun `List with no item type produces unconstrained array (matches runtime)`() {
        val schema = SchemaEmitter.emitDataClassSchema(cls(
            "com.example.Empty",
            listOf(field("xs", FieldType.ListT(itemType = null))),
        ))
        assertEquals(
            """{"type":"object","properties":{"xs":{"type":"array"}},"required":["xs"],"additionalProperties":false}""",
            schema,
        )
    }

    @Test
    fun `nested @Generable reference emits an object placeholder (runtime resolves at read time)`() {
        val schema = SchemaEmitter.emitDataClassSchema(cls(
            "com.example.Order",
            listOf(field("customer", FieldType.GenerableRef("com.example.Customer"))),
        ))
        assertEquals(
            """{"type":"object","properties":{"customer":{"type":"object"}},"required":["customer"],"additionalProperties":false}""",
            schema,
        )
    }

    @Test
    fun `data class with no fields produces empty properties (matches runtime fallback)`() {
        val schema = SchemaEmitter.emitDataClassSchema(cls("com.example.Empty", emptyList()))
        assertEquals(
            """{"type":"object","properties":{},"required":[],"additionalProperties":false}""",
            schema,
        )
    }

    // ── Sealed-root schema generation (#1702) ────────────────────────────────

    private fun variant(
        simpleName: String,
        fields: List<Field>,
        guide: String? = null,
    ) = GenerableClass(
        qualifiedName = "com.example.$simpleName",
        isSealed = false,
        isAbstract = false,
        isInterface = false,
        isEnum = false,
        isAnnotation = false,
        hasPrimaryConstructor = true,
        primaryConstructorParamCount = fields.size,
        fields = fields,
        simpleName = simpleName,
        guideDescription = guide,
    )

    private fun sealedParent(name: String, variants: List<GenerableClass>) = GenerableClass(
        qualifiedName = "com.example.$name",
        isSealed = true,
        isAbstract = true,    // sealed types are abstract from a JVM perspective; emitter ignores this for sealed
        isInterface = false,
        isEnum = false,
        isAnnotation = false,
        hasPrimaryConstructor = false,
        primaryConstructorParamCount = 0,
        simpleName = name,
        sealedVariants = variants,
    )

    @Test
    fun `sealed — two-variant root produces oneOf with discriminators`() {
        val schema = SchemaEmitter.emitSealedSchema(sealedParent("Decision", listOf(
            variant("Approved", listOf(field("confidence", FieldType.DoubleT))),
            variant("Rejected", listOf(field("reason", FieldType.StringT))),
        )))
        assertEquals(
            """{"oneOf":[""" +
                """{"type":"object","properties":{"type":{"type":"string","const":"Approved"},"confidence":{"type":"number"}},"required":["type","confidence"],"additionalProperties":false}""" +
                "," +
                """{"type":"object","properties":{"type":{"type":"string","const":"Rejected"},"reason":{"type":"string"}},"required":["type","reason"],"additionalProperties":false}""" +
                "]}",
            schema,
        )
    }

    @Test
    fun `sealed — variant with no extra params is just the discriminator`() {
        val schema = SchemaEmitter.emitSealedSchema(sealedParent("Status", listOf(
            variant("Pending", emptyList()),
        )))
        assertEquals(
            """{"oneOf":[""" +
                """{"type":"object","properties":{"type":{"type":"string","const":"Pending"}},"required":["type"],"additionalProperties":false}""" +
                "]}",
            schema,
        )
    }

    @Test
    fun `sealed — variant with @Guide description tacked on at end`() {
        val schema = SchemaEmitter.emitSealedSchema(sealedParent("Decision", listOf(
            variant(
                "Approved",
                listOf(field("confidence", FieldType.DoubleT)),
                guide = "the request was accepted",
            ),
        )))
        assertEquals(
            """{"oneOf":[""" +
                """{"type":"object","properties":{"type":{"type":"string","const":"Approved"},"confidence":{"type":"number"}},"required":["type","confidence"],"additionalProperties":false,"description":"the request was accepted"}""" +
                "]}",
            schema,
        )
    }

    @Test
    fun `sealed — nullable param is not in required list`() {
        val schema = SchemaEmitter.emitSealedSchema(sealedParent("Decision", listOf(
            variant("Rejected", listOf(
                field("reason", FieldType.StringT),
                field("notes", FieldType.StringT, nullable = true),
            )),
        )))
        assertEquals(
            """{"oneOf":[""" +
                """{"type":"object","properties":{"type":{"type":"string","const":"Rejected"},"reason":{"type":"string"},"notes":{"type":"string"}},"required":["type","reason"],"additionalProperties":false}""" +
                "]}",
            schema,
        )
    }

    @Test
    fun `sealed — variant with default-valued param is not in required list`() {
        val schema = SchemaEmitter.emitSealedSchema(sealedParent("Decision", listOf(
            variant("Approved", listOf(
                field("confidence", FieldType.DoubleT),
                field("note", FieldType.StringT, hasDefault = true),
            )),
        )))
        assertEquals(
            """{"oneOf":[""" +
                """{"type":"object","properties":{"type":{"type":"string","const":"Approved"},"confidence":{"type":"number"},"note":{"type":"string"}},"required":["type","confidence"],"additionalProperties":false}""" +
                "]}",
            schema,
        )
    }

    @Test
    fun `sealed — empty variants list produces empty oneOf matching runtime`() {
        val schema = SchemaEmitter.emitSealedSchema(sealedParent("NoVariants", emptyList()))
        assertEquals("""{"oneOf":[]}""", schema)
    }

    @Test
    fun `sealed — variant field @Guide and class @Guide both render correctly`() {
        // Both the field-level and class-level @Guide annotations should
        // appear in the right places — field guide inside the type object,
        // class guide as a trailing top-level description.
        val schema = SchemaEmitter.emitSealedSchema(sealedParent("D", listOf(
            variant(
                "Approved",
                listOf(field("confidence", FieldType.DoubleT, guide = "0..1 score")),
                guide = "the variant",
            ),
        )))
        assertEquals(
            """{"oneOf":[""" +
                """{"type":"object","properties":{"type":{"type":"string","const":"Approved"},"confidence":{"type":"number","description":"0..1 score"}},"required":["type","confidence"],"additionalProperties":false,"description":"the variant"}""" +
                "]}",
            schema,
        )
    }
}
