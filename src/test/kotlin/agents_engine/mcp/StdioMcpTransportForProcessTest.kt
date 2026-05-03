package agents_engine.mcp

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Tests for #886 — coverage of StdioMcpTransport.forProcess factory + lambdas.
//
// These tests spawn real child processes (cat / sh) so they only run on
// POSIX systems. Windows runners skip via @EnabledOnOs. CI on ubuntu-latest
// covers the path; native-Windows contributors don't see false failures.
@EnabledOnOs(OS.LINUX, OS.MAC)
class StdioMcpTransportForProcessTest {

    private val transports = mutableListOf<StdioMcpTransport>()

    @AfterTest fun cleanup() { transports.forEach { runCatching { it.close() } } }

    private fun forProcess(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        workingDir: File? = null,
        stderrSink: (String) -> Unit = {},
    ): StdioMcpTransport = StdioMcpTransport.forProcess(command, env, workingDir, stderrSink)
        .also { transports.add(it) }

    @Test
    fun `forProcess spawns a child and round-trips JSON-RPC over stdio (cat echo)`() {
        // `cat` echoes stdin to stdout. Writing a JSON-RPC envelope and reading
        // back via rpc() must return the same envelope, proving the input/output
        // streams are wired correctly through ProcessBuilder.
        val transport = forProcess(listOf("cat"))
        val envelope = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        val response = transport.rpc(envelope)
        assertEquals(envelope, response)
    }

    @Test
    fun `forProcess applies env map to the child process`() {
        // Child reads $TEST_VAR via shell, formats a JSON-RPC response with the
        // value, then exits. If env wasn't applied, $TEST_VAR is empty.
        val transport = forProcess(
            command = listOf("sh", "-c", "printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"%s\"}\\n' \"\$TEST_VAR\""),
            env = mapOf("TEST_VAR" to "applied"),
        )
        // We don't write a request — the child writes the response unprompted
        // and exits. rpc() with id=1 will read the line.
        val response = transport.rpc("""{"jsonrpc":"2.0","id":1,"method":"x"}""")
        assertTrue(response.contains("\"result\":\"applied\""), "env var not applied; got: $response")
    }

    @Test
    fun `forProcess applies workingDir to the child process`() {
        val tmp = File(System.getProperty("java.io.tmpdir"))
        val transport = forProcess(
            command = listOf("sh", "-c", "printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"%s\"}\\n' \"\$(pwd)\""),
            workingDir = tmp,
        )
        val response = transport.rpc("""{"jsonrpc":"2.0","id":1,"method":"x"}""")
        // macOS's TMPDIR resolves to /var/folders/... and `pwd` may report
        // /private/var/folders/... — accept either by checking the trailing path.
        val expected = tmp.canonicalPath
        assertTrue(
            response.contains(expected) || response.contains(tmp.absolutePath),
            "workingDir not applied; expected $expected, got: $response",
        )
    }

    @Test
    fun `forProcess pipes child stderr lines into stderrSink`() {
        val received = ConcurrentLinkedQueue<String>()
        val transport = forProcess(
            command = listOf(
                "sh", "-c",
                """echo err-line-1 >&2; echo err-line-2 >&2; printf '{"jsonrpc":"2.0","id":1,"result":null}\n'""",
            ),
            stderrSink = { received.add(it) },
        )
        transport.rpc("""{"jsonrpc":"2.0","id":1,"method":"x"}""")
        // Stderr drain runs on a daemon thread; brief wait for the pipe to
        // fully flush before asserting.
        val deadline = System.currentTimeMillis() + 1500
        while (received.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(received.contains("err-line-1"), "stderr line 1 not received; got: $received")
        assertTrue(received.contains("err-line-2"), "stderr line 2 not received; got: $received")
    }

    @Test
    fun `forProcess close destroys a long-running child within the wait window`() {
        // sleep 60 stays open until killed. close() should send destroy() and
        // return well within the 2-second waitFor window.
        val transport = forProcess(listOf("sh", "-c", "sleep 60"))
        val started = System.nanoTime()
        transport.close()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(
            elapsedMs < 3_000,
            "close() should kill the child within 3s; took ${elapsedMs}ms",
        )
    }
}
