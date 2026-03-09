package agents_engine.generation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ─── Test domain types ───────────────────────────────────────────────────────

@Generable
data class ScoreResult(
    @Guide("Score from 0.0 to 1.0")
    val score: Double,
    @Guide("One-word verdict: pass or fail")
    val verdict: String,
)

@Generable
data class TaggedResult(
    @Guide("Result tags")
    val tags: List<String>,
    val count: Int,
)

@Generable
data class NestedResult(
    @Guide("The inner score object")
    val inner: ScoreResult,
    val label: String,
)

@Generable
sealed interface Decision {
    @Guide("Code is ready to ship")
    data class Approved(
        @Guide("Confidence score 0.0 to 1.0")
        val confidence: Double,
    ) : Decision

    @Guide("Code needs changes")
    data class Rejected(
        @Guide("Reason for rejection")
        val reason: String,
    ) : Decision
}

// ─── Tests ───────────────────────────────────────────────────────────────────

class GenerableTest {

    // ─── Annotations ─────────────────────────────────────────────────────────

    @Test
    fun `@Generable annotation is present at runtime on class`() {
        assertNotNull(ScoreResult::class.annotations.filterIsInstance<Generable>().firstOrNull())
    }

    @Test
    fun `@Guide annotation is present at runtime on constructor parameter`() {
        val param = ScoreResult::class.constructors.first().parameters.find { it.name == "score" }!!
        val guide = param.annotations.filterIsInstance<Guide>().firstOrNull()
        assertNotNull(guide)
        assertEquals("Score from 0.0 to 1.0", guide!!.description)
    }

    @Test
    fun `@Guide annotation is present at runtime on sealed subclass`() {
        val guide = Decision.Approved::class.annotations.filterIsInstance<Guide>().firstOrNull()
        assertNotNull(guide)
        assertEquals("Code is ready to ship", guide!!.description)
    }

    // ─── jsonSchema — data class ──────────────────────────────────────────────

    @Test
    fun `jsonSchema contains field names`() {
        val schema = ScoreResult::class.jsonSchema()
        assertTrue("score" in schema)
        assertTrue("verdict" in schema)
    }

    @Test
    fun `jsonSchema maps Double to number type`() {
        assertTrue("number" in ScoreResult::class.jsonSchema())
    }

    @Test
    fun `jsonSchema maps String to string type`() {
        assertTrue("string" in ScoreResult::class.jsonSchema())
    }

    @Test
    fun `jsonSchema includes Guide descriptions`() {
        val schema = ScoreResult::class.jsonSchema()
        assertTrue("Score from 0.0 to 1.0" in schema)
        assertTrue("One-word verdict: pass or fail" in schema)
    }

    @Test
    fun `jsonSchema marks non-nullable fields as required`() {
        val schema = ScoreResult::class.jsonSchema()
        val required = schema.substringAfter("required")
        assertTrue("required" in schema)
        assertTrue("score" in required)
        assertTrue("verdict" in required)
    }

    @Test
    fun `jsonSchema maps List to array type`() {
        assertTrue("array" in TaggedResult::class.jsonSchema())
    }

    @Test
    fun `jsonSchema inlines nested Generable class`() {
        val schema = NestedResult::class.jsonSchema()
        assertTrue("object" in schema)
        // nested ScoreResult schema is inlined
        assertTrue("score" in schema)
    }

    // ─── jsonSchema — sealed interface ────────────────────────────────────────

    @Test
    fun `jsonSchema for sealed interface uses oneOf`() {
        assertTrue("oneOf" in Decision::class.jsonSchema())
    }

    @Test
    fun `jsonSchema for sealed interface includes all variant names`() {
        val schema = Decision::class.jsonSchema()
        assertTrue("Approved" in schema)
        assertTrue("Rejected" in schema)
    }

    @Test
    fun `jsonSchema for sealed interface includes Guide descriptions on variants`() {
        val schema = Decision::class.jsonSchema()
        assertTrue("Code is ready to ship" in schema)
        assertTrue("Code needs changes" in schema)
    }

    // ─── promptFragment — data class ─────────────────────────────────────────

    @Test
    fun `promptFragment contains field names`() {
        val prompt = ScoreResult::class.promptFragment()
        assertTrue("score" in prompt)
        assertTrue("verdict" in prompt)
    }

    @Test
    fun `promptFragment contains Guide descriptions`() {
        val prompt = ScoreResult::class.promptFragment()
        assertTrue("Score from 0.0 to 1.0" in prompt)
        assertTrue("One-word verdict: pass or fail" in prompt)
    }

    @Test
    fun `promptFragment contains JSON instruction`() {
        assertTrue("JSON" in ScoreResult::class.promptFragment())
    }

    // ─── promptFragment — sealed interface ───────────────────────────────────

    @Test
    fun `promptFragment for sealed interface contains all variant names`() {
        val prompt = Decision::class.promptFragment()
        assertTrue("Approved" in prompt)
        assertTrue("Rejected" in prompt)
    }

    @Test
    fun `promptFragment for sealed interface contains variant Guide descriptions`() {
        val prompt = Decision::class.promptFragment()
        assertTrue("Code is ready to ship" in prompt)
        assertTrue("Code needs changes" in prompt)
    }

    // ─── fromLlmOutput — clean JSON ──────────────────────────────────────────

    @Test
    fun `fromLlmOutput parses clean JSON`() {
        val result = ScoreResult::class.fromLlmOutput("""{"score": 0.9, "verdict": "pass"}""")
        assertNotNull(result)
        assertEquals(0.9, result!!.score, 0.001)
        assertEquals("pass", result.verdict)
    }

    @Test
    fun `fromLlmOutput handles integer coercion to Double`() {
        val result = ScoreResult::class.fromLlmOutput("""{"score": 1, "verdict": "pass"}""")
        assertNotNull(result)
        assertEquals(1.0, result!!.score, 0.001)
    }

    @Test
    fun `fromLlmOutput handles markdown fences`() {
        val json = "```json\n{\"score\": 0.7, \"verdict\": \"fail\"}\n```"
        val result = ScoreResult::class.fromLlmOutput(json)
        assertNotNull(result)
        assertEquals(0.7, result!!.score, 0.001)
        assertEquals("fail", result.verdict)
    }

    @Test
    fun `fromLlmOutput handles trailing commas`() {
        val result = ScoreResult::class.fromLlmOutput("""{"score": 0.5, "verdict": "pass",}""")
        assertNotNull(result)
        assertEquals(0.5, result!!.score, 0.001)
    }

    @Test
    fun `fromLlmOutput handles extra text surrounding JSON`() {
        val input = """Here is the result: {"score": 1.0, "verdict": "pass"} Hope that helps!"""
        val result = ScoreResult::class.fromLlmOutput(input)
        assertNotNull(result)
        assertEquals(1.0, result!!.score, 0.001)
    }

    @Test
    fun `fromLlmOutput returns null for plain text with no JSON`() {
        assertNull(ScoreResult::class.fromLlmOutput("This is not JSON at all"))
    }

    @Test
    fun `fromLlmOutput parses List field`() {
        val result = TaggedResult::class.fromLlmOutput("""{"tags": ["a", "b", "c"], "count": 3}""")
        assertNotNull(result)
        assertEquals(listOf("a", "b", "c"), result!!.tags)
        assertEquals(3, result.count)
    }

    @Test
    fun `fromLlmOutput parses nested Generable object`() {
        val json = """{"inner": {"score": 0.8, "verdict": "pass"}, "label": "test"}"""
        val result = NestedResult::class.fromLlmOutput(json)
        assertNotNull(result)
        assertEquals(0.8, result!!.inner.score, 0.001)
        assertEquals("pass", result.inner.verdict)
        assertEquals("test", result.label)
    }

    // ─── fromLlmOutput — sealed interface ────────────────────────────────────

    @Test
    fun `fromLlmOutput routes sealed type to Approved variant`() {
        val result = Decision::class.fromLlmOutput("""{"type": "Approved", "confidence": 0.95}""")
        assertNotNull(result)
        assertTrue(result is Decision.Approved)
        assertEquals(0.95, (result as Decision.Approved).confidence, 0.001)
    }

    @Test
    fun `fromLlmOutput routes sealed type to Rejected variant`() {
        val result = Decision::class.fromLlmOutput("""{"type": "Rejected", "reason": "Too complex"}""")
        assertNotNull(result)
        assertTrue(result is Decision.Rejected)
        assertEquals("Too complex", (result as Decision.Rejected).reason)
    }

    @Test
    fun `fromLlmOutput returns null for unknown sealed variant`() {
        assertNull(Decision::class.fromLlmOutput("""{"type": "Unknown", "foo": "bar"}"""))
    }

    @Test
    fun `fromLlmOutput returns null when sealed type discriminator is missing`() {
        assertNull(Decision::class.fromLlmOutput("""{"confidence": 0.9}"""))
    }

    // ─── inline fromLlmOutput ────────────────────────────────────────────────

    @Test
    fun `inline fromLlmOutput works with reified type`() {
        val result: ScoreResult? = fromLlmOutput("""{"score": 0.8, "verdict": "pass"}""")
        assertNotNull(result)
        assertEquals(0.8, result!!.score, 0.001)
    }

    // ─── PartiallyGenerated ───────────────────────────────────────────────────

    @Test
    fun `PartiallyGenerated starts empty`() {
        val partial = partiallyGenerated<ScoreResult>()
        assertTrue(partial.arrivedFieldNames.isEmpty())
    }

    @Test
    fun `PartiallyGenerated withField accumulates fields immutably`() {
        val empty = partiallyGenerated<ScoreResult>()
        val withScore = empty.withField("score", 0.9)
        val withBoth = withScore.withField("verdict", "pass")

        assertTrue(empty.arrivedFieldNames.isEmpty())
        assertEquals(setOf("score"), withScore.arrivedFieldNames)
        assertEquals(setOf("score", "verdict"), withBoth.arrivedFieldNames)
    }

    @Test
    fun `PartiallyGenerated get returns arrived field values`() {
        val partial = partiallyGenerated<ScoreResult>()
            .withField("score", 0.9)
            .withField("verdict", "pass")
        assertEquals(0.9, partial["score"])
        assertEquals("pass", partial["verdict"])
    }

    @Test
    fun `PartiallyGenerated get returns null for missing field`() {
        val partial = partiallyGenerated<ScoreResult>()
        assertNull(partial["score"])
    }

    @Test
    fun `PartiallyGenerated has returns true only for arrived fields`() {
        val partial = partiallyGenerated<ScoreResult>().withField("score", 0.9)
        assertTrue(partial.has("score"))
        assertTrue(!partial.has("verdict"))
    }

    @Test
    fun `PartiallyGenerated toComplete returns T when all fields present`() {
        val partial = partiallyGenerated<ScoreResult>()
            .withField("score", 0.9)
            .withField("verdict", "pass")
        val complete = partial.toComplete()
        assertNotNull(complete)
        assertEquals(0.9, complete!!.score, 0.001)
        assertEquals("pass", complete.verdict)
    }

    @Test
    fun `PartiallyGenerated toComplete returns null when required field missing`() {
        val partial = partiallyGenerated<ScoreResult>().withField("score", 0.9)
        // verdict is required — construction must fail
        assertNull(partial.toComplete())
    }
}
