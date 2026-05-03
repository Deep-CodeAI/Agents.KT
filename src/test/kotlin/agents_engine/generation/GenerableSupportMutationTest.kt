package agents_engine.generation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Mutation-killer fixtures for GenerableSupport — see #840.
// Each fixture is the minimum shape needed to differentiate the two branches
// of a surviving NegateConditional mutation in the schema generator.

// dataClassLlmDescription / sealedLlmDescription — branch on "description is empty".
@Generable("With description annotation")
data class DescribedArgs(
    @Guide("the field guide") val v: String,
)

@Generable
data class UndescribedArgs(val v: String)

// Single-field data class — kills L188 `i < ctor.parameters.size - 1` in
// dataClassPromptFragment. With one param, i=0 and size-1=0, condition false → no comma.
// MathMutator on `size - 1` → `size + 1` makes condition true → produces a stray comma.
@Generable("single field")
data class SingleField(@Guide("only") val only: String)

// Data class with optional + nullable + required fields — exercises variantJsonSchema
// and dataClassJsonSchema "required" filtering decisions.
@Generable("mixed required-ness")
data class MixedFields(
    @Guide("required string") val req: String,
    @Guide("nullable string") val nul: String?,
    @Guide("optional with default") val opt: Int = 7,
)

// Sealed with one subclass that's @Guide-annotated and one that isn't.
@Generable("sealed root")
sealed interface MutationDecision {
    @Generable
    @Guide("first variant has guide")
    data class WithGuide(val payload: String) : MutationDecision

    @Generable
    data class WithoutGuide(val n: Int) : MutationDecision
}

// Data class containing a List of @Generable elements — exercises the
// jsonSchemaTypeObject List branch (L158-163) that asks "does the element type
// have @Generable?" and "does itemType resolve at all?".
@Generable("contains a list of nested Generables")
data class ListOfNested(
    @Guide("nested items") val items: List<DescribedArgs>,
)

// Data class containing a List of plain strings — the type-arg-present branch
// of jsonSchemaTypeObject differs from List with no element type.
@Generable("contains a list of strings")
data class ListOfStrings(
    @Guide("string items") val tags: List<String>,
)

class GenerableSupportMutationTest {

    // dataClassLlmDescription L37, sealedLlmDescription L56/L58 — `if (genDescription.isNotEmpty())`

    @Test
    fun `data class llm description includes the generable description when present`() {
        val out = DescribedArgs::class.toLlmDescription()
        assertTrue(
            out.contains("With description annotation"),
            "non-empty Generable description must be inlined: $out",
        )
    }

    @Test
    fun `data class llm description omits the description block when empty`() {
        val out = UndescribedArgs::class.toLlmDescription()
        // Without a description, the `if (...)` skips the appendLine() pair. The
        // mutated negation would inline an empty description, producing a stray
        // blank line right after `## UndescribedArgs`.
        assertFalse(
            out.contains("\n\n\n"),
            "empty Generable description must NOT inject blank-line block: $out",
        )
        // Sanity that the rest of the output is still produced.
        assertTrue(out.contains("UndescribedArgs"))
    }

    @Test
    fun `sealed llm description includes the generable description when present`() {
        val out = MutationDecision::class.toLlmDescription()
        assertTrue(
            out.contains("sealed root"),
            "non-empty sealed description must be inlined: $out",
        )
    }

    @Test
    fun `sealed llm description includes Guide for variants that have it and a plain header for variants that don't`() {
        // Kills the L56/L58/L67 negate-conditional cluster — Guide present vs absent.
        val out = MutationDecision::class.toLlmDescription()
        assertTrue(
            out.contains("WithGuide: first variant has guide"),
            "guide-annotated variant must show its description: $out",
        )
        assertTrue(
            out.contains("WithoutGuide"),
            "guide-less variant must still show its name: $out",
        )
        assertFalse(
            out.contains("WithoutGuide: "),
            "guide-less variant must NOT have a colon-suffix: $out",
        )
    }

    // dataClassPromptFragment L188 — `i < ctor.parameters.size - 1` decides comma suffix.

    @Test
    fun `prompt fragment for single-field class has no trailing comma`() {
        // L188 ConditionalsBoundary + Math + NegateConditional all on the comma decision.
        val out = SingleField::class.promptFragment()
        // Find the line containing "only" — it must NOT end with a comma.
        val onlyLine = out.lines().single { it.contains("\"only\"") }
        assertFalse(
            onlyLine.trimEnd().endsWith(","),
            "single-field prompt must NOT add a trailing comma: '$onlyLine'",
        )
    }

    @Test
    fun `prompt fragment for multi-field class has commas between fields but not after the last`() {
        val out = MixedFields::class.promptFragment()
        val lines = out.lines().filter { it.contains("\"req\"") || it.contains("\"nul\"") || it.contains("\"opt\"") }
        assertEquals(3, lines.size, "expected three field lines: $lines")
        assertTrue(lines[0].trimEnd().endsWith(","), "first field must have trailing comma: '${lines[0]}'")
        assertTrue(lines[1].trimEnd().endsWith(","), "middle field must have trailing comma: '${lines[1]}'")
        assertFalse(lines[2].trimEnd().endsWith(","), "last field must NOT have trailing comma: '${lines[2]}'")
    }

    // dataClassJsonSchema L108 — `param.type.isMarkedNullable && !it.isOptional` filtering for required[]

    @Test
    fun `data class json schema lists only required (non-nullable, non-optional) fields in required array`() {
        val schema = MixedFields::class.jsonSchema()
        // Find the required[...] segment.
        val requiredBlock = Regex(""""required":\[([^\]]*)\]""").find(schema)?.groupValues?.get(1) ?: ""
        assertTrue(requiredBlock.contains("\"req\""), "required must list 'req': $schema")
        assertFalse(requiredBlock.contains("\"nul\""), "nullable 'nul' must NOT be in required[]: $schema")
        assertFalse(requiredBlock.contains("\"opt\""), "optional-with-default 'opt' must NOT be in required[]: $schema")
    }

    // sealedJsonSchema L120 — first-vs-subsequent variant comma boundary.

    @Test
    fun `sealed json schema separates variants with commas inside the oneOf array`() {
        val schema = MutationDecision::class.jsonSchema()
        assertTrue(schema.contains("\"oneOf\":["), "must produce oneOf: $schema")
        // Two variants → exactly one separator comma at the oneOf-level (between `}` and `{`).
        val oneOfBlock = schema.substringAfter("\"oneOf\":[").substringBeforeLast("]")
        val topLevelCommaCount = countTopLevelCommas(oneOfBlock)
        assertEquals(1, topLevelCommaCount, "exactly 1 separator comma between 2 variants: $oneOfBlock")
    }

    // variantJsonSchema L132 / L137 — ctor != null and required-filter conditions.

    @Test
    fun `variant json schema includes type discriminator and constructor params`() {
        val schema = MutationDecision::class.jsonSchema()
        assertTrue(
            schema.contains(""""const":"WithGuide""""),
            "WithGuide variant must use its simpleName as type const: $schema",
        )
        assertTrue(
            schema.contains(""""const":"WithoutGuide""""),
            "WithoutGuide variant must use its simpleName as type const: $schema",
        )
        assertTrue(schema.contains("\"payload\""), "WithGuide.payload must appear: $schema")
        assertTrue(schema.contains("\"n\""), "WithoutGuide.n must appear: $schema")
    }

    // jsonSchemaTypeObject L158-163 — List<T>, hasAnnotation<Generable>, type-arg presence.

    @Test
    fun `list of strings produces array schema with string items`() {
        val schema = ListOfStrings::class.jsonSchema()
        assertTrue(
            schema.contains(""""type":"array","items":{"type":"string"}"""),
            "list-of-string items must be typed string: $schema",
        )
    }

    @Test
    fun `list of nested generables produces array schema with nested object items`() {
        // Kills L163 — `cls.hasAnnotation<Generable>()` decides "recurse jsonSchema()" vs
        // "fall through to string". Mutated negation makes a nested Generable appear as
        // {"type":"string"} instead of an object schema.
        val schema = ListOfNested::class.jsonSchema()
        assertTrue(
            schema.contains(""""type":"array","items":{"type":"object""""),
            "list-of-Generable items must recurse to object schema: $schema",
        )
        assertTrue(schema.contains("\"v\""), "nested DescribedArgs.v must appear: $schema")
    }

    // constructFromMap L401/L402 — extraKeys check and discriminator-mismatch check.

    @Test
    fun `constructFromMap rejects extra unknown keys`() {
        val result = MixedFields::class.constructFromMap(mapOf("req" to "x", "nul" to null, "opt" to 1, "extra" to "nope"))
        assertNull(result, "extra key 'extra' must reject construction")
    }

    @Test
    fun `constructFromMap rejects sealed-variant discriminator mismatch`() {
        // #699 — type='WithoutGuide' attempting to construct WithGuide must reject.
        val result = MutationDecision.WithGuide::class.constructFromMap(
            mapOf("type" to "WithoutGuide", "payload" to "x"),
        )
        assertNull(result, "discriminator mismatch must reject construction")
    }

    @Test
    fun `constructFromMap accepts matching sealed-variant discriminator`() {
        val result = MutationDecision.WithGuide::class.constructFromMap(
            mapOf("type" to "WithGuide", "payload" to "x"),
        )
        assertNotNull(result, "matching discriminator must construct")
        assertEquals("x", result!!.payload)
    }

    // coerceValue L307 — `arguments.firstOrNull()?.type ?: return items` for List elements.

    @Test
    fun `list field with element coercion preserves typed list elements`() {
        val r = ListOfStrings::class.fromLlmOutput("""{"tags":["a","b","c"]}""")
        assertNotNull(r)
        assertEquals(listOf("a", "b", "c"), r!!.tags)
    }

    private fun countTopLevelCommas(s: String): Int {
        var depth = 0
        var count = 0
        var inString = false
        for (i in s.indices) {
            val c = s[i]
            if (c == '"' && (i == 0 || s[i - 1] != '\\')) inString = !inString
            if (inString) continue
            when (c) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                ',' -> if (depth == 0) count++
            }
        }
        return count
    }
}
