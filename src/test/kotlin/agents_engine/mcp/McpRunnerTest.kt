package agents_engine.mcp

import agents_engine.core.agent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpRunnerTest {

    private val toClose = mutableListOf<AutoCloseable>()
    private val toStop = mutableListOf<() -> Unit>()

    @AfterTest fun cleanup() {
        toClose.forEach { runCatching { it.close() } }
        toStop.forEach { runCatching { it() } }
    }

    private fun trivial() = agent<String, String>("runner-test") {
        skills {
            skill<String, String>("greet", "Greets") { implementedBy { "hi $it" } }
            skill<String, String>("shout", "Shouts") { implementedBy { it.uppercase() } }
        }
    }

    // ────────────────────────────────────────────────────────────
    // Argument parsing (pure, no server)
    // ────────────────────────────────────────────────────────────

    @Test
    fun `empty args produce defaults from configure block`() {
        val cfg = McpRunner.resolveConfig(arrayOf()) {
            port = 8080
            expose("greet")
        }
        assertEquals(8080, cfg.port)
        assertEquals(listOf("greet"), cfg.exposeNames)
        assertEquals(false, cfg.helpRequested)
        assertEquals(false, cfg.versionRequested)
        assertEquals(false, cfg.stdioRequested)
        assertTrue(cfg.errors.isEmpty(), "errors: ${cfg.errors}")
    }

    @Test
    fun `--port overrides block default`() {
        val cfg = McpRunner.resolveConfig(arrayOf("--port", "9999")) {
            port = 8080
            expose("greet")
        }
        assertEquals(9999, cfg.port)
    }

    @Test
    fun `--expose replaces block defaults and accepts multiple`() {
        val cfg = McpRunner.resolveConfig(arrayOf("--expose", "shout", "--expose", "greet")) {
            port = 0
            expose("nope")  // replaced by CLI
        }
        assertEquals(listOf("shout", "greet"), cfg.exposeNames)
    }

    @Test
    fun `--help is recognized via long and short form`() {
        for (flag in listOf("--help", "-h")) {
            val cfg = McpRunner.resolveConfig(arrayOf(flag)) { port = 0 }
            assertTrue(cfg.helpRequested, "$flag should set helpRequested")
        }
    }

    @Test
    fun `--version is recognized via long and short form`() {
        for (flag in listOf("--version", "-V")) {
            val cfg = McpRunner.resolveConfig(arrayOf(flag)) { port = 0 }
            assertTrue(cfg.versionRequested, "$flag should set versionRequested")
        }
    }

    @Test
    fun `--stdio is recognized`() {
        val cfg = McpRunner.resolveConfig(arrayOf("--stdio")) {
            port = 8080
            expose("greet")
        }
        assertTrue(cfg.stdioRequested, "--stdio should select stdio serving")
        assertEquals(8080, cfg.port, "stdio selection should not mutate the configured HTTP default")
        assertEquals(listOf("greet"), cfg.exposeNames)
    }

    @Test
    fun `unknown flag produces an error`() {
        val cfg = McpRunner.resolveConfig(arrayOf("--mystery")) { port = 0 }
        assertTrue(cfg.errors.isNotEmpty())
        assertTrue(cfg.errors.first().contains("--mystery"), "got: ${cfg.errors}")
    }

    @Test
    fun `invalid port value produces an error`() {
        val cfg = McpRunner.resolveConfig(arrayOf("--port", "not-a-number")) { port = 0 }
        assertTrue(cfg.errors.any { it.contains("port", ignoreCase = true) }, "got: ${cfg.errors}")
    }

    @Test
    fun `port out of range produces an error`() {
        val cfg = McpRunner.resolveConfig(arrayOf("--port", "99999")) { port = 0 }
        assertTrue(cfg.errors.any { it.contains("port", ignoreCase = true) }, "got: ${cfg.errors}")
    }

    // ────────────────────────────────────────────────────────────
    // serve() exit codes for control-flow paths
    // ────────────────────────────────────────────────────────────

    @Test
    fun `--help returns exit code 0 without binding a port`() {
        val code = McpRunner.serve(trivial(), arrayOf("--help")) { port = 0; expose("greet") }
        assertEquals(0, code)
    }

    @Test
    fun `--version returns exit code 0 without binding a port`() {
        val code = McpRunner.serve(trivial(), arrayOf("--version")) { port = 0; expose("greet") }
        assertEquals(0, code)
    }

    @Test
    fun `unknown flag returns non-zero exit code without binding a port`() {
        val code = McpRunner.serve(trivial(), arrayOf("--bogus")) { port = 0; expose("greet") }
        assertTrue(code != 0, "expected non-zero, got $code")
    }

    @Test
    fun `stdio serve responds on stdout without HTTP listening line`() {
        val originalIn = System.`in`
        val originalOut = System.out
        val request = """{"jsonrpc":"2.0","id":1,"method":"ping"}""" + "\n"
        val stdout = ByteArrayOutputStream()
        System.setIn(ByteArrayInputStream(request.toByteArray(Charsets.UTF_8)))
        System.setOut(PrintStream(stdout, true))
        try {
            val code = McpRunner.serve(trivial(), arrayOf("--stdio")) {
                expose("greet")
            }
            assertEquals(0, code)
        } finally {
            System.setIn(originalIn)
            System.setOut(originalOut)
        }

        val text = stdout.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("""{"jsonrpc":"2.0""""),
            "stdout must contain only MCP JSON-RPC, got: $text")
        assertTrue(!text.contains("Listening on"), "stdio mode must not print HTTP listening text to stdout: $text")
    }

    // ────────────────────────────────────────────────────────────
    // Real serve() — start, hit, shut down
    // ────────────────────────────────────────────────────────────

    @Test
    fun `serve binds and round-trips through McpClient, then stops cleanly`() {
        val started = AtomicReference<McpServer?>(null)
        val ready = CountDownLatch(1)

        val thread = Thread({
            McpRunner.serve(trivial(), arrayOf()) {
                port = 0
                expose("greet")
                onStarted = { server ->
                    started.set(server)
                    ready.countDown()
                }
            }
        }, "McpRunner-test").apply { isDaemon = true; start() }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "server did not start within 5s")
        val server = started.get()!!
        toStop.add { server.stop() }

        val client = McpClient.connect(server.url).also { toClose.add(it) }
        assertEquals("hi world", client.call("greet", mapOf("input" to "world")))

        // Trigger shutdown by stopping the server; runner thread should exit.
        server.stop()
        thread.join(5_000)
        assertNotNull(started.get(), "started reference should remain")
    }
}
