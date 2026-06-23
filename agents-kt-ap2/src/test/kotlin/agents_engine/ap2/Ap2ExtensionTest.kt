package agents_engine.ap2

import agents_engine.core.agent
import agents_engine.core.skill
import com.nimbusds.jose.util.JSONObjectUtils
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// AP2 (PRD §12.10) spike — advertising AP2 on the A2A AgentCard. A counterparty discovers "this agent speaks
// AP2" by finding the extension URI under capabilities.extensions, exactly as it discovers any A2A capability.
class Ap2ExtensionTest {

    @Test
    fun `advertiseOn adds the AP2 extension and preserves existing capabilities`() {
        val card = mapOf(
            "name" to "shop",
            "capabilities" to mapOf("streaming" to false, "pushNotifications" to false),
        )
        @Suppress("UNCHECKED_CAST")
        val caps = Ap2Extension.advertiseOn(card)["capabilities"] as Map<String, Any?>
        assertEquals(false, caps["streaming"]) // existing capability preserved
        @Suppress("UNCHECKED_CAST")
        val extensions = caps["extensions"] as List<Map<String, Any?>>
        assertEquals(Ap2Extension.URI, extensions.single()["uri"])
        assertEquals(false, extensions.single()["required"])
    }

    @Test
    fun `the AP2 extension advertises onto a real A2AServer AgentCard`() {
        val shopper = agent<String, String>("shopper") {
            skills { skill<String, String>("shop", "Shops on the user's behalf") { implementedBy { "ok: $it" } } }
        }
        val server = agents_engine.a2a.A2AServer.from(shopper).start()
        try {
            val cardUrl = URI(server.url).let { "${it.scheme}://${it.host}:${it.port}/.well-known/agent-card.json" }
            val resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(cardUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, resp.statusCode())

            @Suppress("UNCHECKED_CAST")
            val card = JSONObjectUtils.parse(resp.body()) as Map<String, Any?>
            // the shipped card carries `capabilities` (streaming/pushNotifications) but no extensions yet…
            val advertised = Ap2Extension.advertiseOn(card)
            @Suppress("UNCHECKED_CAST")
            val extensions = (advertised["capabilities"] as Map<String, Any?>)["extensions"] as List<Map<String, Any?>>
            assertTrue(extensions.any { it["uri"] == Ap2Extension.URI }, "AP2 extension URI advertised on the card")
        } finally {
            server.stop()
        }
    }
}
