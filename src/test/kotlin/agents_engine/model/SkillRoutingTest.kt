package agents_engine.model

import agents_engine.core.SkillRoute
import agents_engine.core.SkillRoutingException
import agents_engine.core.agent
import agents_engine.generation.LenientJsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for #641 — LLM skill router returns a structured SkillRoute(name,
 * confidence, rationale) instead of free-text. Threshold enforcement +
 * observability hook for rationale.
 */
class SkillRoutingTest {

    private fun routerJson(name: String, confidence: Double, rationale: String): String =
        """{"skillName":"$name","confidence":$confidence,"rationale":"$rationale"}"""

    @Test
    fun `valid SkillRoute JSON above threshold routes to the chosen skill`() {
        val rationaleEvents = mutableListOf<String>()
        val mock = ModelClient { _ -> LlmResponse.Text(routerJson("greet", 0.9, "user said hello")) }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills {
                skill<String, String>("greet", "Greets") { implementedBy { "hi" } }
                skill<String, String>("farewell", "Says bye") { implementedBy { "bye" } }
            }
            routerRationale { rationaleEvents += it }
        }

        assertEquals("hi", a("hello"))
        assertEquals("user said hello", rationaleEvents.single())
    }

    @Test
    fun `confidence below default threshold throws SkillRoutingException with rationale`() {
        val mock = ModelClient { _ -> LlmResponse.Text(routerJson("greet", 0.3, "really not sure")) }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills {
                skill<String, String>("greet", "Greets") { implementedBy { "hi" } }
                skill<String, String>("farewell", "Says bye") { implementedBy { "bye" } }
            }
        }

        try {
            a("hello")
            fail("expected SkillRoutingException")
        } catch (e: SkillRoutingException) {
            assertTrue(e.message!!.contains("0.3"), "message must include confidence: ${e.message}")
            assertTrue(e.message!!.contains("really not sure"), "message must include rationale: ${e.message}")
        }
    }

    @Test
    fun `custom confidence threshold can require near-certainty`() {
        val mock = ModelClient { _ -> LlmResponse.Text(routerJson("greet", 0.7, "kinda sure")) }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills {
                skill<String, String>("greet", "Greets") { implementedBy { "hi" } }
                skill<String, String>("farewell", "Says bye") { implementedBy { "bye" } }
            }
            skillSelectionConfidenceThreshold(0.95)
        }

        try {
            a("hello"); fail("expected throw at 0.7 < 0.95 threshold")
        } catch (e: SkillRoutingException) {
            assertTrue(e.message!!.contains("0.95"), "message must include threshold: ${e.message}")
        }
    }

    @Test
    fun `unknown skill name in router output throws SkillRoutingException naming the offender`() {
        val mock = ModelClient { _ -> LlmResponse.Text(routerJson("ghost", 0.99, "made up")) }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills {
                skill<String, String>("greet", "Greets") { implementedBy { "hi" } }
                skill<String, String>("farewell", "Says bye") { implementedBy { "bye" } }
            }
        }

        try {
            a("hello"); fail("expected throw on unknown skill name")
        } catch (e: SkillRoutingException) {
            assertTrue(e.message!!.contains("ghost"), "must name the unknown skill: ${e.message}")
        }
    }

    @Test
    fun `malformed JSON falls back to treating text as a plain skill name (back-compat)`() {
        // Some smaller models still emit raw skill names. The router must remain forgiving:
        // if the response isn't valid JSON, treat it as a skill name with confidence = 1.0.
        val mock = ModelClient { _ -> LlmResponse.Text("greet") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills {
                skill<String, String>("greet", "Greets") { implementedBy { "hi" } }
                skill<String, String>("farewell", "Says bye") { implementedBy { "bye" } }
            }
        }

        assertEquals("hi", a("hello"))
    }

    @Test
    fun `SkillRoute is a Generable data class with three required fields`() {
        // Verify the type itself: parses cleanly via LenientJsonParser, has the three fields.
        val map = LenientJsonParser.parse(routerJson("greet", 0.9, "user said hello")) as Map<*, *>
        assertEquals("greet", map["skillName"])
        assertEquals(0.9, map["confidence"])
        assertEquals("user said hello", map["rationale"])
    }
}
