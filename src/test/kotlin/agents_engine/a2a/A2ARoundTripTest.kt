package agents_engine.a2a

import agents_engine.core.agent
import agents_engine.generation.Generable
import agents_engine.generation.LenientJsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

// #3864 — A2A server + typed client, in-process round trips: String IN/OUT,
// @Generable IN/OUT, AgentCard discovery, bearer auth, error mapping.

class A2ARoundTripTest {

    @Generable
    data class Ticket(val subject: String, val priority: Int)

    @Generable
    data class Resolution(val action: String, val escalate: Boolean)

    private fun echoAgent() = agent<String, String>("echo") {
        skills {
            skill<String, String>("echo", "Echoes input") { implementedBy { "echo: $it" } }
        }
    }

    @Test
    fun `string round-trip through server and typed client`() {
        val server = A2AServer.from(echoAgent()).start()
        try {
            val remote = a2aAgent<String, String>("remote-echo", server.url)
            assertEquals("echo: hello", remote("hello"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `typed Generable round-trip — JSON in, JSON out, typed on both ends`() {
        val triage = agent<Ticket, Resolution>("triage") {
            skills {
                skill<Ticket, Resolution>("triage", "Routes tickets") {
                    implementedBy { ticket ->
                        Resolution(action = "route:${ticket.subject}", escalate = ticket.priority > 2)
                    }
                }
            }
        }
        val server = A2AServer.from(triage).start()
        try {
            val remote = a2aAgent<Ticket, Resolution>("remote-triage", server.url)
            val resolution = remote(Ticket(subject = "billing", priority = 3))
            assertEquals("route:billing", resolution.action)
            assertTrue(resolution.escalate)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `agent card is published with skills and protocol version`() {
        val server = A2AServer.from(echoAgent()).start()
        try {
            val cardUrl = server.url.replace("/a2a", "/.well-known/agent-card.json")
            val body = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(cardUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()
            val card = LenientJsonParser.parse(body) as? Map<*, *> ?: fail("card is not JSON: $body")
            assertEquals(A2A_PROTOCOL_VERSION, card["protocolVersion"])
            assertEquals("echo", card["name"])
            val skills = card["skills"] as List<*>
            assertEquals("echo", (skills.single() as Map<*, *>)["id"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun `bearer auth rejects missing token and accepts the right one`() {
        val server = A2AServer.from(echoAgent(), bearerToken = "s3cret").start()
        try {
            val unauthorized = a2aAgent<String, String>("no-token", server.url)
            assertFailsWith<IllegalStateException> { unauthorized("hello") }

            val authorized = a2aAgent<String, String>("with-token", server.url, bearerToken = "s3cret")
            assertEquals("echo: hello", authorized("hello"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `remote agent failure surfaces as a JSON-RPC error, not a hang or empty result`() {
        val failing = agent<String, String>("boom") {
            skills {
                skill<String, String>("explode", "Always throws") {
                    implementedBy { error("remote blew up") }
                }
            }
        }
        val server = A2AServer.from(failing).start()
        try {
            val remote = a2aAgent<String, String>("remote-boom", server.url)
            val e = assertFailsWith<IllegalStateException> { remote("hello") }
            assertTrue("remote blew up" in (e.message ?: ""), "error message must travel; got: ${e.message}")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `unsupported method gets a method-not-found error`() {
        val server = A2AServer.from(echoAgent()).start()
        try {
            val body = """{"jsonrpc":"2.0","id":"1","method":"tasks/cancel","params":{}}"""
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(server.url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()
            assertTrue("-32601" in response, "method-not-found expected; got: $response")
        } finally {
            server.stop()
        }
    }
}
