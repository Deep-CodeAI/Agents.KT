package agents_engine.nlweb

import agents_engine.core.agent
import agents_engine.core.skill
import agents_engine.model.NlWebResult
import agents_engine.model.NlWebSearchResult
import agents_engine.model.parseNlWebResponse
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #4542 (PRD §12.9) — server-side NLWeb. from(agent) mirrors McpServer/A2AServer. The serve side
// mirrors the consume side (nlwebSearch, #4541): what NlWebServer renders, parseNlWebResponse reads
// back. Hermetic — loopback HttpServer, real agents, in-process HTTP round trip (A2ARoundTripTest shape).

class NlWebServerTest {

    private val http = HttpClient.newHttpClient()

    // An agent whose skill returns NLWeb results directly → served verbatim as results[].
    private fun catalogAgent() = agent<String, NlWebSearchResult>("catalog") {
        skills {
            skill<String, NlWebSearchResult>("search", "Returns schema.org matches") {
                implementedBy { q ->
                    NlWebSearchResult(
                        results = listOf(
                            NlWebResult(url = "https://x/1", name = "match: $q", score = 9.0, schemaType = "Recipe"),
                        ),
                        queryId = "q-1",
                    )
                }
            }
        }
    }

    // An agent that just answers → its output becomes the `summary`.
    private fun answerAgent() = agent<String, String>("answerer") {
        skills { skill<String, String>("ask", "Answers") { implementedBy { q -> "answer: $q" } } }
    }

    private fun post(url: String, body: String, bearer: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder().uri(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body))
        bearer?.let { b.header("Authorization", "Bearer $it") }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `agent returning NlWebSearchResult is served as results`() {
        val server = NlWebServer.from(catalogAgent()).start()
        try {
            val resp = post(server.url, """{"query":"pasta","mode":"list"}""")
            assertEquals(200, resp.statusCode())
            val parsed = parseNlWebResponse(resp.body())
            assertEquals("q-1", parsed.queryId)
            val item = parsed.results.single()
            assertEquals("match: pasta", item.name) // query reached the agent
            assertEquals("Recipe", item.schemaType)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `agent returning a plain answer becomes the summary`() {
        val server = NlWebServer.from(answerAgent()).start()
        try {
            val parsed = parseNlWebResponse(post(server.url, """{"query":"hello"}""").body())
            assertEquals("answer: hello", parsed.answer)
            assertTrue(parsed.results.isEmpty(), "plain-answer agent yields no schema.org results")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `rendered response round-trips through the client parser`() {
        val result = NlWebSearchResult(
            results = listOf(NlWebResult(url = "https://a", name = "Alpha", score = 85.0, schemaType = "Article")),
            answer = "one match",
            queryId = "q-2",
        )
        val parsed = parseNlWebResponse(renderAskResponse(result))
        assertEquals("q-2", parsed.queryId)
        assertEquals("one match", parsed.answer)
        assertEquals("Alpha", parsed.results.single().name)
        assertEquals(85.0, parsed.results.single().score)
    }

    @Test
    fun `missing query is a 400`() {
        val server = NlWebServer.from(answerAgent()).start()
        try {
            val resp = post(server.url, """{"mode":"list"}""")
            assertEquals(400, resp.statusCode())
            assertTrue("missing 'query'" in resp.body(), resp.body())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `bearer auth rejects without token and accepts with it`() {
        val server = NlWebServer.from(answerAgent(), bearerToken = "s3cret").start()
        try {
            assertEquals(401, post(server.url, """{"query":"x"}""").statusCode())
            assertEquals(200, post(server.url, """{"query":"x"}""", bearer = "s3cret").statusCode())
        } finally {
            server.stop()
        }
    }
}
