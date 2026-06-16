package agents_engine.x402

import agents_engine.agui.AgUiServer
import agents_engine.core.agent
import agents_engine.core.skill
import agents_engine.nlweb.NlWebServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #4527 — the X402PaymentGate `payment =` wiring on the serve surfaces. Hermetic: a fake facilitator (no
// chain) gates a real agent served over NLWeb and AG-UI. All four serve surfaces wrap their invocation
// handler identically (payment?.gate(handler) ?: handler), so NLWeb + AG-UI cover the pattern.
class X402ServeIntegrationTest {

    private val http = HttpClient.newHttpClient()

    private val okFacilitator = object : FacilitatorClient {
        override fun verify(paymentHeader: String, requirements: PaymentRequirements) =
            FacilitatorVerification(isValid = true, payer = "0xPayer")

        override fun settle(paymentHeader: String, requirements: PaymentRequirements) =
            FacilitatorSettlement(success = true, transaction = "0xTX", network = requirements.network)
    }

    private fun gate(resource: String) = X402PaymentGate(
        PaymentRequirements(
            network = "base", maxAmountRequired = "1", payTo = "0xSeller", asset = "0xUSDC", resource = resource,
        ),
        okFacilitator,
    )

    private fun paidAgent() = agent<String, String>("paid") {
        skills { skill<String, String>("answer", "") { implementedBy { "answer: $it" } } }
    }

    private fun post(url: String, body: String, payment: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder().uri(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body))
        payment?.let { b.header("X-PAYMENT", it) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `NlWebServer gated by x402 demands payment then serves`() {
        val server = NlWebServer.from(paidAgent(), payment = gate("/ask")).start()
        try {
            assertEquals(402, post(server.url, """{"query":"hi"}""").statusCode())
            val paid = post(server.url, """{"query":"hi"}""", payment = "dummy")
            assertEquals(200, paid.statusCode())
            assertTrue("answer: hi" in paid.body(), paid.body())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `AgUiServer gated by x402 demands payment then streams`() {
        val server = AgUiServer.from(paidAgent(), payment = gate("/agent")).start()
        try {
            val body = """{"messages":[{"role":"user","content":"hi"}]}"""
            assertEquals(402, post(server.url, body).statusCode())
            val paid = post(server.url, body, payment = "dummy")
            assertEquals(200, paid.statusCode())
            assertTrue("RUN_STARTED" in paid.body(), paid.body())
        } finally {
            server.stop()
        }
    }
}
