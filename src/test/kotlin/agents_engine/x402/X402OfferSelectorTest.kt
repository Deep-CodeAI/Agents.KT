package agents_engine.x402

import agents_engine.generation.LenientJsonParser
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.math.BigInteger
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

// #4528 (PRD §12.8) — deterministic offer selection. The seller orders accepts[]; paying the FIRST acceptable
// offer lets a seller steer the buyer to the costliest. The selector makes the choice the buyer's. Default =
// lowest amount; the buyer is not at the mercy of the seller's ordering.
class X402OfferSelectorTest {

    private val usdc = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
    private val pk = "0x0000000000000000000000000000000000000000000000000000000000000001"

    private fun offer(amount: String, payTo: String) = PaymentRequirements(
        network = "base-sepolia", maxAmountRequired = amount, payTo = payTo, asset = usdc,
        resource = "https://seller.example/x", extra = mapOf("name" to "USD Coin", "version" to "2"),
    )

    @Test
    fun `LowestAmount picks the cheapest offer, FirstAllowed picks the seller's first`() {
        val offers = listOf(offer("5000", "0xExpensive"), offer("1000", "0xCheap"), offer("3000", "0xMid"))
        assertEquals("1000", X402OfferSelector.LowestAmount.select(offers)?.maxAmountRequired)
        assertEquals("5000", X402OfferSelector.FirstAllowed.select(offers)?.maxAmountRequired)
    }

    // A seller that lists an EXPENSIVE offer first, then a cheaper one; on the paid retry it echoes the signed
    // amount so the test can prove the client paid the cheaper one rather than the seller's first.
    private fun serveTwoOffers(): Pair<String, () -> Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val accepts = listOf(offer("5000", "0x209693Bc6afc0C5328bA36FaF03C514EF312287C"),
            offer("1000", "0x209693Bc6afc0C5328bA36FaF03C514EF312287C"))
            .map { it.toJsonObject() }
        server.createContext("/x", HttpHandler { ex ->
            val header = ex.requestHeaders.getFirst("X-PAYMENT")
            if (header == null) {
                val body = agents_engine.mcp.McpJson.encode(linkedMapOf("x402Version" to 1, "accepts" to accepts))
                    .toByteArray()
                ex.sendResponseHeaders(402, body.size.toLong()); ex.responseBody.use { it.write(body) }
            } else {
                @Suppress("UNCHECKED_CAST")
                val payload = LenientJsonParser.parse(String(Base64.getDecoder().decode(header))) as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val auth = (payload["payload"] as Map<String, Any?>)["authorization"] as Map<String, Any?>
                val body = "paid:${auth["value"]}".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
        })
        server.start()
        return "http://127.0.0.1:${server.address.port}/x" to { server.stop(0) }
    }

    @Test
    fun `the client pays the cheaper of two seller offers (not the first)`() {
        val (url, stop) = serveTwoOffers()
        try {
            val policy = X402SpendPolicy(maxValuePerPayment = BigInteger.valueOf(10_000))
            val account = X402Account.fromPrivateKey(pk, policy)
            val resp = X402Client(account).get(url) // default selector = LowestAmount
            assertEquals(200, resp.statusCode())
            assertEquals("paid:1000", resp.body()) // chose 1000, not the seller's first (5000)
        } finally {
            stop()
        }
    }
}
