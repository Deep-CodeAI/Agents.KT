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

// #1753 — regression guard for cancellation of ClaudeClient.chatStream.
// Same contract pin as the Ollama analog: Kotlin Flow's channel-backed
// emit propagates collector cancellation back into the producer body,
// closing the InputStream before the next blocking read.

class ClaudeClientCancellationTest {

    private class SlowSseStream : InputStream() {
        private val events = buildList {
            add("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}\n\n")
            (1..6).forEach { i ->
                add("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\"}}\n\n")
                add("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"chunk$i \"}}\n\n")
                add("event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n")
            }
            add("event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":12}}\n\n")
            add("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        }.toMutableList()
        private var bytes: ByteArray = events.removeFirst().toByteArray(Charsets.UTF_8)
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
            if (events.isEmpty()) return -1
            try { Thread.sleep(80) } catch (_: InterruptedException) { throw IOException("interrupted") }
            bytes = events.removeFirst().toByteArray(Charsets.UTF_8)
            bytesPos = 0
            return bytes[bytesPos].toInt() and 0xFF
        }
    }

    @Test
    fun `cancelling Claude chatStream mid-collect terminates within tight window, not the full response`() = runBlocking {
        val stubbed = object : ClaudeClient(apiKey = "test-key", model = "test-model") {
            override fun sendChatStream(body: String, headers: Map<String, String>): InputStream = SlowSseStream()
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

        // SSE has more events per "chunk" (start + delta + stop) so the
        // first text delta might arrive 2-3 stub-yields in. Bound at
        // 400ms — generous slack vs. the full stream's ~1.5s.
        assertTrue(
            cancelMs < 400,
            "expected cancel to return within ~400ms; took ${cancelMs}ms",
        )
    }
}
