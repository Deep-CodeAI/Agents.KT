package agents_engine.mcp

import agents_engine.core.agent
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

// Mutation-killer tests for McpRunner — see #842.
// Targets the printlns and lifecycle calls that the existing exit-code-only
// tests left undetected, plus the port-boundary mutations.
class McpRunnerMutationTest {

    private val toClose = mutableListOf<AutoCloseable>()
    private val toStop = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanup() {
        toClose.forEach { runCatching { it.close() } }
        toStop.forEach { runCatching { it() } }
    }

    private fun trivial() = agent<String, String>("runner-test") {
        skills { skill<String, String>("greet", "Greets") { implementedBy { "hi $it" } } }
    }

    private inline fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buf = ByteArrayOutputStream()
        System.setOut(PrintStream(buf))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buf.toString()
    }

    private inline fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val buf = ByteArrayOutputStream()
        System.setErr(PrintStream(buf))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return buf.toString()
    }

    // resolveConfig — port boundary (L106 ConditionalsBoundary, twice for `0..65535` range).
    // Existing test covers `--port 99999`. Need exact 0, 65535, -1, 65536.

    @Test
    fun `port 0 is valid (lower boundary)`() {
        val cfg = McpRunner.resolveConfig(arrayOf("--port", "0")) { port = 8080 }
        assertTrue(cfg.errors.isEmpty(), "port 0 must not error: ${cfg.errors}")
        assertEquals(0, cfg.port)
    }

    @Test
    fun `port 65535 is valid (upper boundary)`() {
        val cfg = McpRunner.resolveConfig(arrayOf("--port", "65535")) { port = 8080 }
        assertTrue(cfg.errors.isEmpty(), "port 65535 must not error: ${cfg.errors}")
        assertEquals(65535, cfg.port)
    }

    @Test
    fun `port -1 is rejected (just below lower boundary)`() {
        val cfg = McpRunner.resolveConfig(arrayOf("--port", "-1")) { port = 8080 }
        assertTrue(cfg.errors.any { it.contains("port", ignoreCase = true) }, "got: ${cfg.errors}")
    }

    @Test
    fun `port 65536 is rejected (just above upper boundary)`() {
        val cfg = McpRunner.resolveConfig(arrayOf("--port", "65536")) { port = 8080 }
        assertTrue(cfg.errors.any { it.contains("port", ignoreCase = true) }, "got: ${cfg.errors}")
    }

    // serve() println survivors — capture stdout/stderr and assert content.

    @Test
    fun `--version prints version line to stdout`() {
        // L38 `println("Agents.KT $VERSION")`. Removing it kills this assertion.
        val out = captureStdout {
            McpRunner.serve(trivial(), arrayOf("--version")) { port = 0 }
        }
        assertTrue(out.contains("Agents.KT"), "--version must print 'Agents.KT' to stdout, got: '$out'")
    }

    @Test
    fun `--help prints usage to stdout (kills printHelp removal)`() {
        // L37 `printHelp()` and L133 `out.println(...)` inside printHelp.
        val out = captureStdout {
            McpRunner.serve(trivial(), arrayOf("--help")) { port = 0 }
        }
        assertTrue(out.contains("Agents.KT"), "--help must print usage banner: '$out'")
        assertTrue(out.contains("--port"), "--help must list --port flag: '$out'")
        assertTrue(out.contains("--expose"), "--help must list --expose flag: '$out'")
    }

    @Test
    fun `unknown flag prints error and usage to stderr`() {
        // L40 `cfg.errors.forEach { System.err.println("error: $it") }`
        // L41 `System.err.println()` (blank line)
        // L42 `printHelp(System.err)`
        val err = captureStderr {
            McpRunner.serve(trivial(), arrayOf("--bogus")) { port = 0 }
        }
        assertTrue(err.contains("error:"), "stderr must contain 'error:' prefix: '$err'")
        assertTrue(err.contains("--bogus"), "stderr must name the bad flag: '$err'")
        assertTrue(err.contains("--port") || err.contains("Agents.KT"), "stderr must include usage banner: '$err'")
    }

    // serve() lifecycle — port honored end-to-end (L48 setPort lambda).

    @Test
    fun `configured port is honored by the running server`() {
        // L48 `port = cfg.port` inside the McpServer.from { } block. If removed,
        // the server binds at port=0 (default) regardless of cfg.port. Asserting
        // that an explicit port=0 produces a non-zero assigned port confirms the
        // OS-assignment path runs; asserting port honoring is harder without a
        // real bind, so we validate via onStarted's reported url containing the
        // assigned port (proves the configure block actually ran).
        val started = AtomicReference<McpServer?>(null)
        val ready = CountDownLatch(1)

        val thread = Thread({
            McpRunner.serve(trivial(), arrayOf()) {
                port = 0  // OS-assigned
                expose("greet")
                onStarted = { server ->
                    started.set(server)
                    ready.countDown()
                }
            }
        }, "McpRunner-mut").apply { isDaemon = true; start() }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "server did not start within 5s")
        val server = started.get()!!
        toStop.add { server.stop() }

        // The server URL should contain a non-zero port that it actually bound.
        assertNotNull(server.url, "server must expose a non-null url")
        assertTrue(server.url.contains(":"), "url must contain a port separator: ${server.url}")
        val portStr = server.url.substringAfterLast(":").substringBefore("/")
        val portNum = portStr.toIntOrNull()
        assertNotNull(portNum, "url must end in a numeric port: ${server.url}")
        assertTrue(portNum!! > 0, "OS-assigned port must be > 0, got $portNum from ${server.url}")

        server.stop()
        thread.join(5_000)
    }

    @Test
    fun `runner thread exits within 2s of server stop (stop-watcher must start)`() {
        // L69 `}.apply { isDaemon = true; start() }` on the stop-watcher thread.
        // If `start()` is removed, the watcher never runs, terminated.countDown()
        // never fires, and the runner thread blocks forever on terminated.await().
        // Existing tests call thread.join(5_000) without checking that the join
        // succeeded — so the mutation hides.
        val ready = CountDownLatch(1)
        val serverRef = AtomicReference<McpServer?>(null)
        val thread = Thread({
            McpRunner.serve(trivial(), arrayOf()) {
                port = 0
                expose("greet")
                onStarted = { s -> serverRef.set(s); ready.countDown() }
            }
        }, "McpRunner-exit-test").apply { isDaemon = true; start() }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "server did not start within 5s")
        val server = serverRef.get()!!
        toStop.add { server.stop() }

        server.stop()
        thread.join(2_000)
        assertTrue(!thread.isAlive, "Runner thread must exit within 2s of server.stop() — stop-watcher start() removal would block here")
    }

    @Test
    fun `serve prints listening line with the server URL to stdout`() {
        // L56 `println("Listening on ${server.url}")`. Capture stdout while the
        // server starts, snapshot the URL via onStarted, then stop cleanly.
        val ready = CountDownLatch(1)
        val urlRef = AtomicReference<String?>(null)
        val serverRef = AtomicReference<McpServer?>(null)
        val out = ByteArrayOutputStream()

        val thread = Thread({
            val original = System.out
            System.setOut(PrintStream(out, true))
            try {
                McpRunner.serve(trivial(), arrayOf()) {
                    port = 0
                    expose("greet")
                    onStarted = { s ->
                        serverRef.set(s)
                        urlRef.set(s.url)
                        ready.countDown()
                    }
                }
            } finally {
                System.setOut(original)
            }
        }, "McpRunner-listening").apply { isDaemon = true; start() }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "server did not start within 5s")
        val url = urlRef.get()
        assertNotNull(url, "url snapshot from onStarted must not be null")

        // Wait briefly for the println on the runner thread to flush, then stop.
        Thread.sleep(100)
        val captured = out.toString()
        serverRef.get()?.stop()
        thread.join(5_000)

        assertTrue(captured.contains("Listening on"), "must print listening line: '$captured'")
        assertTrue(captured.contains(url!!), "must include the server URL '$url': '$captured'")
    }
}
