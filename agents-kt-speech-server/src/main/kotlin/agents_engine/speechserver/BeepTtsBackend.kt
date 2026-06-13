package agents_engine.speechserver

/**
 * #4506 — demo TTS backend: returns a short WAV beep (a 440 Hz tone) instead of real
 * synthesis, so `java -jar` answers `/v1/audio/speech` out of the box with real,
 * playable audio. Always emits WAV regardless of the requested format. Swap for a
 * real backend (sherpa-onnx for pure-jar TTS, or a forward-to-Qwen proxy) in
 * production — see the module README.
 */
class BeepTtsBackend(private val toneHz: Double = 440.0, private val millis: Int = 250) : ServerTtsBackend {
    override fun synthesize(text: String, voice: String?, format: String): ByteArray = toneWav(toneHz, millis)
}
