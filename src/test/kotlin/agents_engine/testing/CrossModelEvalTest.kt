package agents_engine.testing

import agents_engine.core.agent
import agents_engine.model.LlmResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #3876 — cross-model regression: the same suite over labeled per-model
// agents, divergence detection, markdown matrix. Hermetic via scripted clients.

class CrossModelEvalTest {

    private fun modelAgent(name: String, vararg replies: String) = agent<String, String>(name) {
        model {
            ollama("stub")
            client = DeterministicModelClient(*replies.map { LlmResponse.Text(it) }.toTypedArray())
        }
        skills { skill<String, String>("answer", "Answers") { tools() } }
    }

    private fun suite() = evalSuite("summary-quality") {
        +eval<String, String>("mentions-subject") {
            input("summarize: the manifest")
            expect { "manifest" in it }
        }
        +eval<String, String>("is-short") {
            input("summarize: the manifest")
            expect { it.length < 40 }
        }
    }

    @Test
    fun `divergent cases are flagged across models`() {
        val result = suite().runAcrossModels(
            // Each case consumes one scripted reply, in declaration order.
            "good-model" to modelAgent("good", "the manifest, summarized", "short"),
            "drifty-model" to modelAgent("drifty", "something unrelated", "short"),
        )

        assertTrue(!result.allPassed)
        assertEquals(listOf("mentions-subject"), result.divergent, "only the first case diverges")
        val markdown = result.toMarkdown()
        assertTrue("⚠️" in markdown && "❌" in markdown && "✅" in markdown, markdown)
        assertTrue("Divergent cases" in markdown, markdown)
    }

    @Test
    fun `all models passing yields no divergence and a clean report`() {
        val result = suite().runAcrossModels(
            "a" to modelAgent("a", "manifest one", "ok"),
            "b" to modelAgent("b", "manifest two", "ok"),
        )
        assertTrue(result.allPassed)
        assertEquals(emptyList(), result.divergent)
        assertTrue("No cross-model divergence." in result.toMarkdown())
    }

    @Test
    fun `duplicate labels fail loud`() {
        assertFailsWith<IllegalArgumentException> {
            suite().runAcrossModels(
                "same" to modelAgent("x", "a", "b"),
                "same" to modelAgent("y", "a", "b"),
            )
        }
    }
}
