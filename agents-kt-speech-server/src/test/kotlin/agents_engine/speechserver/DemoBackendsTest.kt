package agents_engine.speechserver

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #4506 — the demo backends make `java -jar` answer both endpoints out of the box:
// fixed-text STT and a real, playable WAV beep from TTS.

class DemoBackendsTest {

    @Test
    fun `fixed-text stt reports the payload it received`() {
        val out = FixedTextSttBackend().transcribe("0123456789".toByteArray(), "audio/wav")
        assertTrue("10 bytes" in out && "audio/wav" in out, out)
    }

    @Test
    fun `beep tts returns a parseable wav`() {
        val wav = BeepTtsBackend(millis = 100).synthesize("ignored", voice = null, format = "wav")
        // It must be a real WAV the JDK can parse (proves a valid header + PCM data).
        val ais = AudioSystem.getAudioInputStream(ByteArrayInputStream(wav))
        assertEquals(1, ais.format.channels, "mono")
        assertEquals(16_000f, ais.format.sampleRate)
        assertTrue(wav.size > 44, "has PCM data beyond the 44-byte header")
    }
}
