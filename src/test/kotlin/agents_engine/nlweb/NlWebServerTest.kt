package agents_engine.nlweb

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

// #4542 (PRD §12.9) — server-side NLWeb. The serve side mirrors the consume side (nlwebSearch,
// #4541): what NlWebServer renders, parseNlWebResponse reads back. Hermetic — loopback HttpServer,
// stub handler, in-process HTTP round trip (the A2ARoundTripTest pattern).

class NlWebServerTest {

    private val http = HttpClient.newHttpClient()

    private fun sample() = NlWebSearchResult(
        results = listOf(
            NlWebResult(url = "https://x/ep/42", name = "AI Safety", site = "podcasts", score = 85.0,
                description = "alignment talk", schemaType = "PodcastEpisode"),
            NlWebResult(url = "https://x/ep/7", name = "RAG 101"),
        ),
        answer = "Two AI podcasts.",
        queryId = "q-1",
    )

    private fun post(url: String, body: String, bearer: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder().uri(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body))
        bearer?.let { b.header("Authorization", "Bearer $it") }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `rendered response round-trips through the client parser`() {
        // What the server emits, the nlwebSearch client must be able to read back.
        val parsed = parseNlWebResponse(renderAskResponse(sample()))
        assertEquals("q-1", parsed.queryId)
        assertEquals("Two AI podcasts.", parsed.answer)
        assertEquals(listOf("https://x/ep/42", "https://x/ep/7"), parsed.results.map { it.url })
        val first = parsed.results.first()
        assertEquals("AI Safety", first.name)
        assertEquals(85.0, first.score)
        assertEquals("PodcastEpisode", first.schemaType)
    }

    @Test
    fun `POST ask returns the handler result and the query reaches the handler`() {
        var seenQuery: String? = null
        val server = NlWebServer.from({ req -> seenQuery = req.query; sample() }).start()
        try {
            val resp = post(server.url, """{"query":"AI podcasts","mode":"list"}""")
            assertEquals(200, resp.statusCode())
            assertEquals("AI podcasts", seenQuery)
            val parsed = parseNlWebResponse(resp.body())
            assertEquals(2, parsed.results.size)
            assertEquals("Two AI podcasts.", parsed.answer)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `missing query is a 400`() {
        val server = NlWebServer.from({ sample() }).start()
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
        val server = NlWebServer.from({ sample() }, bearerToken = "s3cret").start()
        try {
            assertEquals(401, post(server.url, """{"query":"x"}""").statusCode())
            assertEquals(200, post(server.url, """{"query":"x"}""", bearer = "s3cret").statusCode())
        } finally {
            server.stop()
        }
    }
}
