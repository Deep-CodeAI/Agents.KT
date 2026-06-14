package agents_engine.whisper

import agents_engine.content.AudioMime
import agents_engine.content.Content
import agents_engine.content.InMemoryBlobStore
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #4505 — WhisperJniSttClient decodes WAV → mono float PCM on the JVM and delegates
// to a WhisperBackend. Tested with a FAKE backend (no native lib / model needed):
// proves the BlobStore resolution + WAV decode + delegation, hermetically.

class WhisperJniSttClientTest {

    /** Minimal PCM 16-bit mono WAV around [samples] at [rate] Hz. */
    private fun monoWav(samples: ShortArray, rate: Int): ByteArray {
        val dataLen = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataLen); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1)        // PCM
        buf.putShort(1)                                                       // mono
        buf.putInt(rate); buf.putInt(rate * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataLen)
        samples.forEach { buf.putShort(it) }
        return ByteArrayOutputStream().also { it.write(buf.array()) }.toByteArray()
    }

    @Test
    fun `decodes a mono wav and passes samples plus rate to the backend`() {
        val blobs = InMemoryBlobStore()
        // 0.5, -0.5, 0.0 at full scale.
        val wav = monoWav(shortArrayOf(16384, -16384, 0), rate = 16_000)
        val ref = blobs.put(wav, AudioMime.Wav.wireMime)

        var seenRate = -1
        var seen: FloatArray = FloatArray(0)
        val backend = WhisperBackend { samples, rate -> seen = samples; seenRate = rate; "transcript" }

        val text = WhisperJniSttClient(backend).transcribe(Content.Audio(ref = ref, mime = AudioMime.Wav), blobs)

        assertEquals("transcript", text)
        assertEquals(16_000, seenRate, "sample rate decoded from the WAV header")
        assertEquals(3, seen.size, "three frames")
        assertEquals(0.5f, seen[0], 0.001f)
        assertEquals(-0.5f, seen[1], 0.001f)
        assertEquals(0.0f, seen[2], 0.001f)
    }

    @Test
    fun `rejects non-wav audio with an actionable message`() {
        val blobs = InMemoryBlobStore()
        val ref = blobs.put("not-a-wav".toByteArray(), AudioMime.Mp3.wireMime)
        val backend = WhisperBackend { _, _ -> "unused" }
        val ex = assertFailsWith<IllegalArgumentException> {
            WhisperJniSttClient(backend).transcribe(Content.Audio(ref = ref, mime = AudioMime.Mp3), blobs)
        }
        assertTrue("ffmpeg" in ex.message.orEmpty(), "tells the user how to convert: ${ex.message}")
    }
}
