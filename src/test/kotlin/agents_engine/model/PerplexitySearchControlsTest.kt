package agents_engine.model

import agents_engine.generation.Generable
import agents_engine.generation.Guide
import agents_engine.generation.LenientJsonParser
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #3677 — unit tests for the `perplexitySearch` search controls + structured
 * output. All pure: each control is asserted to serialize to the exact
 * Perplexity request param via [buildPerplexitySearchBody].
 */
class PerplexitySearchControlsTest {

    private fun bodyOf(options: PerplexitySearchOptions): Map<*, *> =
        LenientJsonParser.parse(buildPerplexitySearchBody("q", options)) as Map<*, *>

    @Test
    fun `bare options omit every control (backward-compatible body)`() {
        val root = bodyOf(PerplexitySearchOptions())
        assertEquals("sonar", root["model"])
        assertNull(root["search_mode"])
        assertNull(root["search_recency_filter"])
        assertNull(root["search_domain_filter"])
        assertNull(root["web_search_options"])
        assertNull(root["reasoning_effort"])
        assertNull(root["response_format"])
    }

    @Test
    fun `search_mode serializes academic and sec`() {
        assertEquals("academic", bodyOf(PerplexitySearchOptions(mode = SearchMode.ACADEMIC))["search_mode"])
        assertEquals("sec", bodyOf(PerplexitySearchOptions(mode = SearchMode.SEC))["search_mode"])
    }

    @Test
    fun `search_recency_filter serializes the window`() {
        assertEquals("week", bodyOf(PerplexitySearchOptions(recency = SearchRecency.WEEK))["search_recency_filter"])
        assertEquals("month", bodyOf(PerplexitySearchOptions(recency = SearchRecency.MONTH))["search_recency_filter"])
    }

    @Test
    fun `search_domain_filter encodes allow plain and deny with a leading dash`() {
        val filter = bodyOf(
            PerplexitySearchOptions(
                domainsAllow = listOf("arxiv.org", "nature.com"),
                domainsDeny = listOf("reddit.com"),
            ),
        )["search_domain_filter"] as List<*>
        assertEquals(listOf("arxiv.org", "nature.com", "-reddit.com"), filter)
    }

    @Test
    fun `web_search_options carries the search_context_size`() {
        val root = bodyOf(PerplexitySearchOptions(contextSize = SearchContextSize.HIGH))
        val wso = root["web_search_options"] as Map<*, *>
        assertEquals("high", wso["search_context_size"])
    }

    @Test
    fun `reasoning_effort serializes lowercase`() {
        val root = bodyOf(PerplexitySearchOptions(reasoningEffort = ReasoningEffort.HIGH))
        assertEquals("high", root["reasoning_effort"])
    }

    @Test
    fun `structured output emits a strict response_format json_schema`() {
        val options = perplexitySearchOptions { structuredOutput(FactSheet::class) }
        val rf = bodyOf(options)["response_format"] as Map<*, *>
        assertEquals("json_schema", rf["type"])
        val schema = rf["json_schema"] as Map<*, *>
        assertEquals("FactSheet", schema["name"])
        assertEquals(true, schema["strict"])
        assertTrue(schema["schema"] is Map<*, *>, "schema must be an embedded JSON object")
    }

    @Test
    fun `structuredOutput rejects a non-Generable type`() {
        val ex = assertThrows<IllegalArgumentException> {
            perplexitySearchOptions { structuredOutput(NotGenerable::class) }
        }
        assertTrue(ex.message!!.contains("@Generable"), ex.message ?: "")
    }

    @Test
    fun `builder DSL round-trips every control into the options`() {
        val options = perplexitySearchOptions {
            model = "sonar-pro"
            mode = SearchMode.ACADEMIC
            recency = SearchRecency.WEEK
            contextSize = SearchContextSize.HIGH
            reasoningEffort = ReasoningEffort.MEDIUM
            allowDomains("arxiv.org")
            denyDomains("reddit.com")
        }
        assertEquals("sonar-pro", options.model)
        assertEquals(SearchMode.ACADEMIC, options.mode)
        assertEquals(SearchRecency.WEEK, options.recency)
        assertEquals(SearchContextSize.HIGH, options.contextSize)
        assertEquals(ReasoningEffort.MEDIUM, options.reasoningEffort)
        assertEquals(listOf("arxiv.org"), options.domainsAllow)
        assertEquals(listOf("reddit.com"), options.domainsDeny)
    }

    @Generable("A small fact sheet")
    data class FactSheet(
        @Guide("the headline fact") val fact: String,
        @Guide("confidence 0-1") val confidence: Double,
    )

    data class NotGenerable(val x: Int)
}
