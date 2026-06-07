package agents_engine.model

import agents_engine.generation.LenientJsonParser
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #3676 — unit tests for the `perplexitySearch` tool. No network: the response
 * parser and request-body builder are pure, and the tool is exercised through
 * an injected mock [PerplexitySearchBackend].
 */
class PerplexitySearchTest {

    // ---- request body --------------------------------------------------

    @Test
    fun `request body carries the model and a JSON-escaped query`() {
        val body = buildPerplexitySearchBody(
            query = "what's new in \"Kotlin 2.4\"?",
            options = PerplexitySearchOptions(model = "sonar-pro"),
        )
        val root = LenientJsonParser.parse(body) as Map<*, *>
        assertEquals("sonar-pro", root["model"])
        val messages = root["messages"] as List<*>
        val user = messages.single() as Map<*, *>
        assertEquals("user", user["role"])
        assertEquals("what's new in \"Kotlin 2.4\"?", user["content"])
    }

    // ---- response parsing ----------------------------------------------

    @Test
    fun `parses answer and rich sources from search_results`() {
        val result = parsePerplexitySearchResponse(
            """
            {"choices":[{"message":{"role":"assistant","content":"Kotlin 2.4 shipped in 2026."}}],
             "search_results":[
               {"title":"Kotlin 2.4 released","url":"https://kotlinlang.org/2-4","snippet":"…","date":"2026-05-01"},
               {"title":"Release notes","url":"https://kotlinlang.org/notes"}
             ]}
            """.trimIndent(),
        )

        assertEquals("Kotlin 2.4 shipped in 2026.", result.answer)
        assertEquals(2, result.sources.size)
        assertEquals("https://kotlinlang.org/2-4", result.sources[0].url)
        assertEquals("Kotlin 2.4 released", result.sources[0].title)
        assertEquals("2026-05-01", result.sources[0].date)
        assertEquals("https://kotlinlang.org/notes", result.sources[1].url)
    }

    @Test
    fun `falls back to citations when search_results is absent`() {
        val result = parsePerplexitySearchResponse(
            """
            {"choices":[{"message":{"content":"Answer."}}],
             "citations":["https://a.example","https://b.example"]}
            """.trimIndent(),
        )

        assertEquals("Answer.", result.answer)
        assertEquals(listOf("https://a.example", "https://b.example"), result.sources.map { it.url })
        assertTrue(result.sources.all { it.title == null }, "citations carry URL only")
    }

    @Test
    fun `prefers search_results over citations when both present`() {
        val result = parsePerplexitySearchResponse(
            """
            {"choices":[{"message":{"content":"x"}}],
             "search_results":[{"title":"T","url":"https://rich.example"}],
             "citations":["https://plain.example"]}
            """.trimIndent(),
        )
        assertEquals(listOf("https://rich.example"), result.sources.map { it.url })
    }

    @Test
    fun `error envelope raises PerplexitySearchException`() {
        val ex = assertThrows<PerplexitySearchException> {
            parsePerplexitySearchResponse("""{"error":{"type":"invalid_request","message":"bad model"}}""")
        }
        assertTrue(ex.message!!.contains("bad model"), "expected provider message: ${ex.message}")
    }

    // ---- rendering -----------------------------------------------------

    @Test
    fun `render lists the answer then numbered sources`() {
        val rendered = PerplexitySearchResult(
            answer = "The sky is blue.",
            sources = listOf(
                PerplexitySource(url = "https://one.example", title = "One"),
                PerplexitySource(url = "https://two.example"),
            ),
        ).toString()

        assertTrue(rendered.startsWith("The sky is blue."))
        assertTrue(rendered.contains("[1] One — https://one.example"))
        assertTrue(rendered.contains("[2] https://two.example"))
    }

    @Test
    fun `render omits the Sources section when there are no sources`() {
        val rendered = PerplexitySearchResult(answer = "No sources here.", sources = emptyList()).toString()
        assertEquals("No sources here.", rendered)
    }

    // ---- the tool ------------------------------------------------------

    private val cannedResult = PerplexitySearchResult(
        answer = "Paris is the capital of France.",
        sources = listOf(PerplexitySource(url = "https://fr.example", title = "France")),
    )

    @Test
    fun `tool is untrusted-output and typed on the query arg`() {
        val tool = perplexitySearchTool(apiKey = "k", backend = { _, _ -> cannedResult })
        assertEquals("perplexity_search", tool.name)
        assertTrue(tool.untrustedOutput, "web search output must be flagged untrusted (#642)")
        assertEquals(PerplexitySearchArgs::class, tool.argsType)
    }

    @Test
    fun `tool passes the query to the backend and returns the grounded result`() {
        var seen: String? = null
        val tool = perplexitySearchTool(apiKey = "k", backend = { q, _ -> seen = q; cannedResult })

        val result = tool.executor(mapOf("query" to "capital of France?"))

        assertEquals("capital of France?", seen)
        assertEquals(cannedResult, result)
    }

    @Test
    fun `the loop renders the tool result inside the untrusted envelope`() {
        // Faithful to AgenticLoop.kt:710 — renderToolResultForLlm then wrapUntrustedToolResult.
        val tool = perplexitySearchTool(apiKey = "k", backend = { _, _ -> cannedResult })
        val result = tool.executor(mapOf("query" to "x"))

        val rendered = ToolResultRendering.renderToolResultForLlm(result)
        val wrapped = ToolResultRendering.wrapUntrustedToolResult(tool.name, rendered)

        val envelope = LenientJsonParser.parse(wrapped) as Map<*, *>
        assertEquals("perplexity_search", envelope["tool"])
        assertEquals(false, envelope["trusted"])
        assertTrue((envelope["value"] as String).contains("Paris is the capital of France."))
        assertTrue((envelope["value"] as String).contains("https://fr.example"))
    }

    @Test
    fun `blank query returns an ERROR string without calling the backend`() {
        var called = false
        val tool = perplexitySearchTool(apiKey = "k", backend = { _, _ -> called = true; cannedResult })
        val result = tool.executor(mapOf("query" to "   "))
        assertEquals("ERROR: missing 'query'", result)
        assertTrue(!called, "backend must not be called for a blank query")
    }

    @Test
    fun `backend failure surfaces as an ERROR string, not an exception`() {
        val tool = perplexitySearchTool(
            apiKey = "k",
            backend = { _, _ -> throw PerplexitySearchException("boom") },
        )
        val result = tool.executor(mapOf("query" to "x"))
        assertTrue((result as String).startsWith("ERROR: perplexity_search failed: boom"), result)
    }
}
