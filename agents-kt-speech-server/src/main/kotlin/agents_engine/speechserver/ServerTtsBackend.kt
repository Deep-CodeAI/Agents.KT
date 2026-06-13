package agents_engine.speechserver

/**
 * #4506 — the TTS inference seam for [SpeechServer]. Returns the synthesized audio
 * **bytes** in the requested [format] (`wav`/`mp3`/…); the server sets the response
 * `Content-Type` from [format]. Plug a pure-jar voice (sherpa-onnx Kokoro/Piper) or a
 * forward-to-Qwen backend — there is no pure-JVM Qwen-TTS, so a Qwen voice means
 * proxying its endpoint here. See the module README.
 *
 * [voice] is null when the request omitted it.
 */
fun interface ServerTtsBackend {
    fun synthesize(text: String, voice: String?, format: String): ByteArray
}
