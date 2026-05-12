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
}
