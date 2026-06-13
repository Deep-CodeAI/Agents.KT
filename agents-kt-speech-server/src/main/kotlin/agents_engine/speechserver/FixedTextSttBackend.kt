package agents_engine.speechserver

/**
 * #4506 — demo STT backend: returns a fixed, descriptive line instead of real
 * transcription, so `java -jar` answers `/v1/audio/transcriptions` out of the box.
 * Swap for a real backend (whisper-jni) in production — see the module README.
 */
class FixedTextSttBackend(private val label: String = "[demo speech-server]") : ServerSttBackend {
    override fun transcribe(audio: ByteArray, contentType: String): String =
        "$label received ${audio.size} bytes of $contentType (no model wired — plug a real ServerSttBackend)"
}
