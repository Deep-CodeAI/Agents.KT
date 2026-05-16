package agents_engine.mcp

import agents_engine.core.agent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.test.assertTrue

/**
 * #1754 — self-contained live-MCP test. Replaces the previous "needs
 * MCP_REDMINE_URL" requirement with a loopback fixture using the
 * framework's own MCP server + client end-to-end.
 *
 * Shape:
 * 1. Build a tool agent `algebra` whose one skill computes
 *    `sqrt(π / e)` to a configurable number of digits.
 * 2. Expose it via `McpServer.from(algebra)` on an auto-assigned
 *    loopback port.
 * 3. Connect an `McpClient` to the server's URL.
 * 4. Invoke the exposed tool over the MCP wire and assert the
 *    returned digits match the canonical sqrt(π/e) to 30 places.
 *
 * Per the design call: π and e are stored as digit arrays (the
 * "custom algebra" digits-as-arrays shape); the actual arithmetic
 * runs on BigInteger.
 */
class LoopbackMcpAlgebraTest {

    private var mcpServer: McpServer? = null
    private var mcpClient: McpClient? = null

    @AfterEach
    fun teardown() {
        mcpClient?.close()
        mcpServer?.stop()
    }

    @Tag("live-mcp")
    @Test
    fun `loopback — algebra agent exposes sqrt(pi over e) over MCP and an MCP client receives the digits`() {
        val algebra = agent<String, String>("algebra") {
            skills {
                skill<String, String>("compute", "Computes sqrt(pi/e) to many decimal places") {
                    implementedBy { _ -> computeSqrtPiOverE(scale = 50) }
                }
            }
        }

        val server = McpServer.from(algebra) {
            port = 0
            expose("compute")
        }.start().also { mcpServer = it }

        val mcp = McpClient.connect(server.url).also { mcpClient = it }

        // Call the tool over the MCP wire — String-input skill schema is
        // `{input: string}`, so we pass an arbitrary input string (the
        // skill ignores it).
        val result = mcp.call("compute", mapOf("input" to "go"))?.toString()
            ?: error("MCP call returned null")

        // sqrt(π/e) starts 1.0750476... — verified against Math.sqrt
        // as a double-precision sanity check, plus a self-consistency
        // square-back below for the high-precision tail.
        assertTrue(
            result.startsWith("1.0750476"),
            "expected MCP round-trip to return sqrt(π/e) starting with \"1.0750476\"; got: \"$result\"",
        )

        // Math.sqrt(Math.PI / Math.E) as a low-precision floor sanity check.
        // Locale.ROOT forces a period decimal separator regardless of host locale.
        val approx = Math.sqrt(Math.PI / Math.E)
        val approxRounded = String.format(java.util.Locale.ROOT, "%.7f", approx)
        assertTrue(
            result.startsWith(approxRounded),
            "MCP-returned digits must agree with Math.sqrt(Math.PI/Math.E) at 7-decimal precision; " +
                "expected prefix \"$approxRounded\"; got: \"$result\"",
        )

        // Self-consistency: take 30 leading decimal digits of result,
        // square them in BigDecimal, compare to π/e to ~25 digits. Catches
        // any deeper precision corruption from the MCP wire.
        val resultBD = java.math.BigDecimal(result.take(33))  // "1." + 30 digits
        val squared = resultBD.multiply(resultBD).setScale(25, java.math.RoundingMode.HALF_UP)
        val piBD = java.math.BigDecimal(PI_DIGITS.joinToString("").let { it.substring(0, 1) + "." + it.substring(1) })
        val eBD = java.math.BigDecimal(E_DIGITS.joinToString("").let { it.substring(0, 1) + "." + it.substring(1) })
        val expectedRatio = piBD.divide(eBD, 25, java.math.RoundingMode.HALF_UP)
        val diff = squared.subtract(expectedRatio).abs()
        assertTrue(
            diff < java.math.BigDecimal("1e-20"),
            "result² must equal π/e to ~20 decimal places; squared=$squared expected=$expectedRatio diff=$diff",
        )
    }

    /**
     * sqrt(π/e) to `scale` digits past the decimal point. π and e are
     * stored as digit arrays per the design; the actual arithmetic runs
     * on BigInteger.
     *
     * Strategy:
     * - Treat the digit arrays as integers: pi = 3141592..., e = 2718281...
     * - Each carries an implicit decimal shift equal to (digits - 1).
     * - To get sqrt(π/e) × 10^scale, compute:
     *     numerator = pi × 10^(eShift + 2*scale - piShift)
     *     scaled    = numerator / e             (integer division)
     *     root      = floor(sqrt(scaled))
     *   `root` is then sqrt(π/e) × 10^scale (as an integer); we
     *   format it with the decimal point after the first digit.
     */
    private fun computeSqrtPiOverE(scale: Int): String {
        val pi = BigInteger(PI_DIGITS.joinToString(""))
        val e = BigInteger(E_DIGITS.joinToString(""))
        val piShift = PI_DIGITS.size - 1
        val eShift = E_DIGITS.size - 1

        val exponent = eShift + 2 * scale - piShift
        require(exponent >= 0) { "scale too large for the digit array sizes" }
        val tenPow = BigInteger.TEN.pow(exponent)
        val scaled = pi.multiply(tenPow).divide(e)
        val root = scaled.sqrt() // floor(sqrt) — exact for square inputs, off-by-one for the last digit otherwise

        val s = root.toString()
        return s.substring(0, 1) + "." + s.substring(1)
    }

    companion object {
        // π to 60 digits past the decimal (61 digit-array entries total).
        // 3.14159265358979323846264338327950288419716939937510582097494
        private val PI_DIGITS = intArrayOf(
            3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8, 9, 7, 9, 3, 2, 3, 8, 4,
            6, 2, 6, 4, 3, 3, 8, 3, 2, 7, 9, 5, 0, 2, 8, 8, 4, 1, 9, 7,
            1, 6, 9, 3, 9, 9, 3, 7, 5, 1, 0, 5, 8, 2, 0, 9, 7, 4, 9, 4,
            4,
        )

        // e to 60 digits past the decimal (61 digit-array entries total).
        // 2.71828182845904523536028747135266249775724709369995957496696
        private val E_DIGITS = intArrayOf(
            2, 7, 1, 8, 2, 8, 1, 8, 2, 8, 4, 5, 9, 0, 4, 5, 2, 3, 5, 3,
            6, 0, 2, 8, 7, 4, 7, 1, 3, 5, 2, 6, 6, 2, 4, 9, 7, 7, 5, 7,
            2, 4, 7, 0, 9, 3, 6, 9, 9, 9, 5, 9, 5, 7, 4, 9, 6, 6, 9, 6,
            7,
        )
    }
}
