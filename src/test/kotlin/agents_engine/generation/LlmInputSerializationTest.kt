package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Tests for #937 — toLlmInput serializes typed agent input as JSON when the
// type is @Generable; falls back to toString() for plain types; preserves
// literal String passthrough so free-form prompts don't get JSON-quoted.

@Generable("a single field")
data class InputSingle(val v: String)

@Generable("multiple primitive fields")
data class InputMulti(val name: String, val count: Int, val active: Boolean)

@Generable("contains a list")
data class InputWithList(val tags: List<String>)

@Generable("contains a nested generable")
data class InputWithNested(val inner: InputSingle, val label: String)

@Generable("sealed root")
sealed interface Decision937 {
    @Generable("approved")
    data class Approved(val confidence: Double) : Decision937

    @Generable("rejected")
    data class Rejected(val reason: String) : Decision937
}

class PlainNonGenerable(val v: String) {
    override fun toString(): String = "PLAIN-$v"
}

class LlmInputSerializationTest {

    @Test
    fun `null serializes as JSON null literal`() {
        assertEquals("null", toLlmInput(null))
    }

    @Test
    fun `String passes through unchanged (no JSON quoting)`() {
        // Free-form prompts must not get wrapped in quotes — that would
        // change what the LLM sees from the user message.
        assertEquals("hello world", toLlmInput("hello world"))
    }

    @Test
    fun `String with quotes and backslashes still passes through unchanged`() {
        // Top-level String is opaque; the agent passed it as-is, the LLM
        // sees it as-is.
        val s = "she said \"hi\" \\ then left"
        assertEquals(s, toLlmInput(s))
    }

    @Test
    fun `primitive Number renders as JSON literal`() {
        assertEquals("42", toLlmInput(42))
        assertEquals("3.14", toLlmInput(3.14))
        assertEquals("9999999999", toLlmInput(9_999_999_999L))
    }

    @Test
    fun `Boolean renders as JSON literal`() {
        assertEquals("true", toLlmInput(true))
        assertEquals("false", toLlmInput(false))
    }

    @Test
    fun `Generable single-field data class serializes as JSON object`() {
        val out = toLlmInput(InputSingle("hello"))
        assertEquals("""{"v":"hello"}""", out)
    }

    @Test
    fun `Generable multi-field data class serializes with each constructor param`() {
        val out = toLlmInput(InputMulti(name = "alice", count = 3, active = true))
        // Field order follows constructor order.
        assertEquals("""{"name":"alice","count":3,"active":true}""", out)
    }

    @Test
    fun `String fields inside Generable are JSON-escaped`() {
        val out = toLlmInput(InputSingle("she said \"hi\" then \\left"))
        assertEquals("""{"v":"she said \"hi\" then \\left"}""", out)
    }

    @Test
    fun `Generable with List field renders the list as JSON array`() {
        val out = toLlmInput(InputWithList(listOf("a", "b", "c")))
        assertEquals("""{"tags":["a","b","c"]}""", out)
    }

    @Test
    fun `Generable with nested Generable field recurses`() {
        val out = toLlmInput(InputWithNested(inner = InputSingle("inside"), label = "outer"))
        assertEquals("""{"inner":{"v":"inside"},"label":"outer"}""", out)
    }

    @Test
    fun `Sealed Generable variant gets a type discriminator`() {
        val approved = toLlmInput(Decision937.Approved(0.92))
        assertTrue(
            approved.contains("\"type\":\"Approved\""),
            "sealed-variant must include type discriminator: $approved",
        )
        assertTrue(approved.contains("\"confidence\":0.92"), "must include field: $approved")

        val rejected = toLlmInput(Decision937.Rejected("not enough data"))
        assertTrue(
            rejected.contains("\"type\":\"Rejected\""),
            "sealed-variant must include type discriminator: $rejected",
        )
        assertTrue(rejected.contains("\"reason\":\"not enough data\""))
    }

    @Test
    fun `non-Generable plain class falls back to toString`() {
        val out = toLlmInput(PlainNonGenerable("xyz"))
        assertEquals("PLAIN-xyz", out)
    }

    @Test
    fun `top-level List of Strings renders as JSON array`() {
        val out = toLlmInput(listOf("a", "b", "c"))
        assertEquals("""["a","b","c"]""", out)
    }

    @Test
    fun `top-level List of Generables renders as JSON array of objects`() {
        val out = toLlmInput(listOf(InputSingle("x"), InputSingle("y")))
        assertEquals("""[{"v":"x"},{"v":"y"}]""", out)
    }

    @Test
    fun `top-level Map renders as JSON object`() {
        val out = toLlmInput(mapOf("a" to 1, "b" to "two"))
        assertEquals("""{"a":1,"b":"two"}""", out)
    }

    // Round-trip: serialized output should re-parse via fromLlmOutput.
    @Test
    fun `round-trip toLlmInput then fromLlmOutput reconstructs the original`() {
        val original = InputMulti(name = "alice", count = 3, active = true)
        val json = toLlmInput(original)
        val reconstructed = InputMulti::class.fromLlmOutput(json)
        assertNotNull(reconstructed)
        assertEquals(original, reconstructed)
    }

    @Test
    fun `round-trip works for sealed variant`() {
        val original: Decision937 = Decision937.Rejected("insufficient")
        val json = toLlmInput(original)
        val reconstructed = Decision937::class.fromLlmOutput(json)
        assertEquals(original, reconstructed)
    }
}
