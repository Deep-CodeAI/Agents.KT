package agents_engine.whisper

/**
 * #4505 — the native seam. A [WhisperBackend] turns decoded PCM samples into text;
 * `WhisperJniSttClient` owns the JVM side (BlobStore resolution, WAV decoding) and
 * delegates the actual inference here. Keeping the binding behind a one-method
 * interface means this module ships **no native artifact and no weights** — the
 * consumer supplies a backend backed by whisper.cpp's JNI lib (see the module
 * README for the ~15-line implementation).
 *
 * [samples] are mono float PCM in `[-1.0, 1.0]` at [sampleRate] Hz. whisper.cpp
 * expects 16 kHz; a backend that wraps it should resample if [sampleRate] differs
 * (or require 16 kHz and document it).
 */
fun interface WhisperBackend {
    fun transcribe(samples: FloatArray, sampleRate: Int): String
}
