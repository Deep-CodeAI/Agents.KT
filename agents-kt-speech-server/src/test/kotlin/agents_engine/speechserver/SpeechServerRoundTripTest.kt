package agents_engine.speechserver

import agents_engine.content.AudioMime
import agents_engine.content.Content
import agents_engine.content.InMemoryBlobStore
import agents_engine.model.QwenTtsClient
import agents_engine.model.WhisperSttClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #4506 — the whole loop, in one JVM, no Docker/Python: Agents.KT's own
// OpenAI-compatible clients (WhisperSttClient / QwenTtsClient) talk to the pure-JDK
// SpeechServer over real HTTP. Fake backends stand in for the model so the test is
// hermetic; the wire (multipart in, JSON/audio out) is exercised for real.

class SpeechServerRoundTripTest {

    private var seenAudio: ByteArray = ByteArray(0)
    private var seenContentType: String = ""
    private val stt = ServerSttBackend { audio, ct ->
        seenAudio = audio; seenContentType = ct; "the meeting is at noon"
    }

    private var spoken: String? = null
    private val ttsBytes = "SYNTH-AUDIO-BYTES".toByteArray()
    private val tts = ServerTtsBackend { text, _, _ -> spoken = text; ttsBytes }

    private val server = SpeechServer(stt, tts, port = 0).start()
    private val baseUrl get() = "http://127.0.0.1:${server.port}"

    @AfterTest fun stop() = server.stop(0)

    @Test
    fun `WhisperSttClient transcribes through the pure-jdk server`() {
        val blobs = InMemoryBlobStore()
        val ref = blobs.put("RIFFfakewav".toByteArray(), AudioMime.Wav.wireMime)

        val transcript = WhisperSttClient(baseUrl).transcribe(Content.Audio(ref = ref, mime = AudioMime.Wav), blobs)

        assertEquals("the meeting is at noon", transcript, "server's STT result round-trips back to the client")
        assertEquals("RIFFfakewav", String(seenAudio), "the file bytes survived the multipart round trip")
        assertEquals("audio/wav", seenContentType, "the part's content-type reached the backend")
    }

    @Test
    fun `QwenTtsClient synthesizes through the pure-jdk server`() {
        val blobs = InMemoryBlobStore()
        val audio = QwenTtsClient(baseUrl, blobs, voice = "Cherry", responseFormat = "wav").speak("hello there")

        assertEquals("hello there", spoken, "the input text reached the TTS backend")
        assertEquals(AudioMime.Wav, audio.mime)
        assertTrue(ttsBytes.contentEquals(blobs.get(audio.ref)), "synthesized bytes round-trip into the BlobStore")
    }

    @Test
    fun `preflight succeeds against the server`() {
        WhisperSttClient(baseUrl).preflight()
        QwenTtsClient(baseUrl, InMemoryBlobStore()).preflight()
    }
}
