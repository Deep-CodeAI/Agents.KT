package agents_engine.speechserver

/**
 * #4506 — the STT inference seam for [SpeechServer]. The server owns the HTTP/wire
 * side (multipart parse, JSON response); the actual transcription is plugged here so
 * the server stays pure-JDK with no model dependency. Wrap whisper-jni / sherpa-onnx
 * (both pure-jar with self-loading native libs) — see the module README.
 *
 * [audio] is the raw uploaded file bytes; [contentType] is its declared MIME
 * (e.g. `audio/wav`). Returns the transcript text.
 */
fun interface ServerSttBackend {
    fun transcribe(audio: ByteArray, contentType: String): String
}
