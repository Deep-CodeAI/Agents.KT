package agents_engine.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertTrue

// #1753 — regression guard for cancellation of OllamaClient.chatStream.
//
// The Kotlin Flow contract (channel-backed by `flowOn(Dispatchers.IO)`)
// already propagates collector cancellation back through `emit()` —
// when the consuming coroutine cancels, emit throws CancellationException,
// the flow body unwinds, and BufferedReader.useLines + .use { stream }
// close the InputStream cleanly. The IO-thread loop never enters its
// next blocking `readLine` after cancel fires.
//
// This test pins that contract: a slow-yielding stub produces six
// chunks at 80ms each (~480ms total). Cancelling after the first
// chunk arrives must return within ~250ms — proves we're not blocking
// on the remaining chunks. If a future change adds a blocking sync
// step that bypasses the flow's cancellation hook, this test fires.

class OllamaClientCancellationTest {

    private class SlowNdjsonStream : InputStream() {
        private val chunks = (1..6).map {
            """{"model":"t","message":{"role":"assistant","content":"chunk$it "},"done":false}""" + "\n"
        }.toMutableList().also {
            it += """{"model":"t","message":{"content":""},"done":true,"prompt_eval_count":3,"eval_count":6}""" + "\n"
        }
        private var bytes: ByteArray = chunks.removeFirst().toByteArray(Charsets.UTF_8)
        private var bytesPos: Int = 0

        override fun read(): Int = if (bytesPos < bytes.size) bytes[bytesPos++].toInt() and 0xFF else loadNextOrEof()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesPos >= bytes.size) {
                val advanced = loadNextOrEof()
                if (advanced < 0) return -1
            }
            val available = bytes.size - bytesPos
            val copyLen = minOf(len, available)
            System.arraycopy(bytes, bytesPos, b, off, copyLen)
            bytesPos += copyLen
            return copyLen
        }
        private fun loadNextOrEof(): Int {
            if (chunks.isEmpty()) return -1
            try { Thread.sleep(80) } catch (_: InterruptedException) { throw IOException("interrupted") }
            bytes = chunks.removeFirst().toByteArray(Charsets.UTF_8)
            bytesPos = 0
            return bytes[bytesPos].toInt() and 0xFF
        }
    }

    @Test
    fun `cancelling chatStream mid-collect terminates within one-chunk's worth of time, not the full response`() = runBlocking {
        val stubbed = object : OllamaClient(model = "test-model") {
            override fun sendChatStream(body: String): InputStream = SlowNdjsonStream()
        }

        var received = 0
        val firstChunk = CompletableDeferred<Unit>()

        val collectJob = launch(Dispatchers.Default) {
            stubbed.chatStream(listOf(LlmMessage("user", "Hi"))).collect { _ ->
                received++
                if (!firstChunk.isCompleted) firstChunk.complete(Unit)
            }
        }

        withTimeout(2000) { firstChunk.await() }
        assertTrue(received >= 1, "expected at least one chunk before cancel; got $received")

        val cancelStartNs = System.nanoTime()
        collectJob.cancelAndJoin()
        val cancelMs = (System.nanoTime() - cancelStartNs) / 1_000_000

        // Bound: under 250ms covers one-chunk-worth (~80ms) + cleanup
        // overhead. The full stream would take ~480ms — we must not wait
        // for it.
        assertTrue(
            cancelMs < 250,
            "expected cancel to return within ~250ms (one chunk's worth + slack); took ${cancelMs}ms",
        )
    }
}
