package agents_engine.model

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// #4541 (PRD §12.9) — the nlwebSearch tool. Hermetic: pure build/parse wire helpers + the tool
// exercised through an injected backend (no network). Mirrors the perplexitySearch test shape.

class NlWebSearchTest {

    @Test
    fun `buildNlWebAskBody includes query, mode, streaming and omits a null site`() {
        val body = buildNlWebAskBody("podcasts about \"AI\"", NlWebSearchOptions())
        assertTrue("\"query\":\"podcasts about \\\"AI\\\"\"" in body, body)
        assertTrue("\"mode\":\"list\"" in body, body)
        assertTrue("\"streaming\":false" in body, body)
        assertTrue("\"site\"" !in body, "null site omitted: $body")
    }

    @Test
    fun `buildNlWebAskBody emits site and lowercased mode when set`() {
        val body = buildNlWebAskBody("q", NlWebSearchOptions(site = "podcasts", mode = NlWebMode.GENERATE))
        assertTrue("\"site\":\"podcasts\"" in body, body)
        assertTrue("\"mode\":\"generate\"" in body, body)
    }

    @Test
    fun `parseNlWebResponse parses results, query_id, and schema type`() {
        val json = """
            {"query_id":"abc123","results":[
              {"url":"https://x/ep/42","name":"AI Safety","site":"podcasts","score":85,
               "description":"alignment talk","schema_object":{"@type":"PodcastEpisode","name":"AI Safety"}}
            ]}
        """.trimIndent()
        val r = parseNlWebResponse(json)
        assertEquals("abc123", r.queryId)
        assertNull(r.answer)
        val item = r.results.single()
        assertEquals("https://x/ep/42", item.url)
        assertEquals("AI Safety", item.name)
        assertEquals("podcasts", item.site)
        assertEquals(85.0, item.score)
        assertEquals("alignment talk", item.description)
        assertEquals("PodcastEpisode", item.schemaType)
    }

    @Test
    fun `parseNlWebResponse picks up a summarize answer and skips a result with no url`() {
        val json = """{"summary":"Two AI podcasts.","results":[{"name":"no-url"},{"url":"https://y"}]}"""
        val r = parseNlWebResponse(json)
        assertEquals("Two AI podcasts.", r.answer)
        assertEquals(listOf("https://y"), r.results.map { it.url })
    }

    @Test
    fun `parseNlWebResponse raises on an error envelope`() {
        assertThrows<NlWebSearchException> { parseNlWebResponse("""{"error":"site not configured"}""") }
        assertThrows<NlWebSearchException> { parseNlWebResponse("""{"error":{"message":"bad query"}}""") }
    }

    @Test
    fun `render formats answer then numbered results`() {
        val out = NlWebSearchResult(
            results = listOf(
                NlWebResult(url = "https://a", name = "Alpha", description = "first", schemaType = "Recipe"),
                NlWebResult(url = "https://b", name = "Beta"),
            ),
            answer = "Here are two.",
        ).render()
        assertTrue(out.startsWith("Here are two."), out)
        assertTrue("[1] Alpha (Recipe) — first" in out, out)
        assertTrue("https://a" in out && "[2] Beta" in out && "https://b" in out, out)
    }

    @Test
    fun `tool is untrusted, returns the rendered result via the backend, and errors on blank query`() {
        val canned = NlWebSearchResult(results = listOf(NlWebResult(url = "https://x", name = "X")))
        val tool = nlwebSearchTool(baseUrl = "https://example.com", backend = { _, _ -> canned })

        assertTrue(tool.untrustedOutput, "nlweb_search must be untrustedOutput (web content = injection vector)")
        assertEquals("nlweb_search", tool.name)

        val ok = tool.executor(mapOf("query" to "anything"))
        assertTrue(ok is NlWebSearchResult && ok.results.single().url == "https://x", "got: $ok")

        assertEquals("ERROR: missing 'query'", tool.executor(mapOf("query" to "  ")))
    }

    @Test
    fun `tool returns an ERROR string when the backend fails`() {
        val tool = nlwebSearchTool(
            baseUrl = "https://example.com",
            backend = { _, _ -> throw NlWebSearchException("connection refused") },
        )
        val out = tool.executor(mapOf("query" to "q"))
        assertTrue(out is String, "expected ERROR string, got: $out")
        assertTrue(out.startsWith("ERROR: nlweb_search failed:") && "connection refused" in out, out)
    }
}
