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

// #1753 — regression guard for cancellation of OpenAiClient.chatStream.
// Same contract pin: Kotlin Flow's emit propagates collector cancellation
// back into the producer body, closing the InputStream before the next
// blocking read.

class OpenAiClientCancellationTest {

    private class SlowOpenAiSseStream : InputStream() {
        private val events = buildList {
            (1..6).forEach { i ->
                add("data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"chunk$i \"},\"finish_reason\":null}]}\n\n")
            }
            add("data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
            add("data: {\"id\":\"x\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":12,\"total_tokens\":15}}\n\n")
            add("data: [DONE]\n\n")
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
    fun `cancelling OpenAI chatStream mid-collect terminates within tight window, not the full response`() = runBlocking {
        val stubbed = object : OpenAiClient(apiKey = "test-key", model = "test-model") {
            override fun sendChatStream(body: String, headers: Map<String, String>): InputStream = SlowOpenAiSseStream()
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

        assertTrue(
            cancelMs < 250,
            "expected cancel to return within ~250ms; took ${cancelMs}ms",
        )
    }
}
