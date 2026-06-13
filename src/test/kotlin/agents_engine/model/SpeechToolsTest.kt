package agents_engine.model

import agents_engine.content.AudioMime
import agents_engine.content.Content
import agents_engine.content.InMemoryBlobStore
import agents_engine.content.ToolResult
import agents_engine.core.ToolRisk
import agents_engine.core.agent
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #4501 — multimodal AS TOOLS. transcribe_audio reads a local audio file (confined
// to an audio root) and runs STT; speak runs TTS and returns a ToolResult carrying
// the typed Content.Audio. End-to-end through the agentic loop with a stub model
// that drives the tool calls.

@OptIn(ExperimentalPathApi::class)
class SpeechToolsTest {

    /** A fake STT that echoes the resolved bytes — proves the file reached the client via the store. */
    private val echoStt = SpeechToTextClient { audio, store -> "heard: " + String(store.get(audio.ref)!!) }

    /** A fake TTS that records calls and stores deterministic bytes. */
    private class RecordingTts(private val store: InMemoryBlobStore) : TtsModelClient {
        val spoken = mutableListOf<String>()
        override fun speak(text: String): Content.Audio {
            spoken += text
            val ref = store.put("AUDIO[$text]".toByteArray(), AudioMime.Wav.wireMime)
            return Content.Audio(ref = ref, mime = AudioMime.Wav)
        }
    }

    @Test
    fun `transcribe_audio loads a file under the root and returns the transcript`() {
        val dir = createTempDirectory("audioroot")
        try {
            val file = dir.resolve("call.wav")
            file.writeBytes("RIFFspoken".toByteArray())
            val blobs = InMemoryBlobStore()
            val tool = transcribeAudioTool(echoStt, blobs, audioRoot = dir.toString())

            assertEquals(ToolRisk.MEDIUM, tool.risk, "declares Medium risk")
            val result = tool.executor(mapOf("path" to file.toString()))
            assertEquals("heard: RIFFspoken", result)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `transcribe_audio refuses a path outside the audio root`() {
        val dir = createTempDirectory("audioroot2")
        val outside = createTempDirectory("outside")
        try {
            val escape = outside.resolve("secret.wav")
            escape.writeBytes("nope".toByteArray())
            val tool = transcribeAudioTool(echoStt, InMemoryBlobStore(), audioRoot = dir.toString())
            val ex = assertFailsWith<IllegalArgumentException> { tool.executor(mapOf("path" to escape.toString())) }
            assertTrue("outside the allowed audio directory" in ex.message.orEmpty(), "got: ${ex.message}")
        } finally {
            dir.deleteRecursively(); outside.deleteRecursively()
        }
    }

    @Test
    fun `speak returns a ToolResult carrying text plus the typed audio with bytes in the store`() {
        val blobs = InMemoryBlobStore()
        val tts = RecordingTts(blobs)
        val tool = speakTool(tts)

        val result = tool.executor(mapOf("text" to "good morning"))
        assertTrue(result is ToolResult, "got: $result")
        val parts = (result as ToolResult).parts
        assertTrue(parts.any { it is Content.Text }, "has a text confirmation")
        val audio = parts.filterIsInstance<Content.Audio>().single()
        assertEquals("AUDIO[good morning]", String(blobs.get(audio.ref)!!), "synthesized bytes survive in the store")
        assertEquals(listOf("good morning"), tts.spoken)
    }

    @Test
    fun `speak rejects blank text`() {
        val tool = speakTool(RecordingTts(InMemoryBlobStore()))
        assertFailsWith<IllegalStateException> { tool.executor(mapOf("text" to "  ")) }
    }

    @Test
    fun `end-to-end — a model drives the speak tool through the agentic loop`() {
        val blobs = InMemoryBlobStore()
        val tts = RecordingTts(blobs)
        // Stub model: first turn calls speak("the answer is 42"), second turn finishes.
        val queue = ArrayDeque(
            listOf(
                LlmResponse.ToolCalls(listOf(ToolCall("speak", mapOf("text" to "the answer is 42")))),
                LlmResponse.Text("spoken"),
            ),
        )
        val mock = object : ModelClient {
            override fun chat(messages: List<LlmMessage>): LlmResponse = queue.removeFirst()
        }
        val voicebot = agent<String, String>("voicebot") {
            model { ollama("stub"); client = mock }
            tools { +speakTool(tts) }
            skills {
                skill<String, String>("assist", "Answers aloud") {
                    @Suppress("DEPRECATION")
                    tools("speak")
                }
            }
        }

        assertEquals("spoken", voicebot("say the answer"))
        assertEquals(listOf("the answer is 42"), tts.spoken, "the model's speak call ran through the loop")
    }
}
