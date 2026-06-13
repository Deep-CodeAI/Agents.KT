package agents_engine.speechserver

/**
 * #4506 — `java -jar agents-kt-speech-server.jar [--port N] [--host H]` (or
 * `./gradlew :agents-kt-speech-server:run --args="--port 8000"`). Starts the pure-JDK
 * OpenAI-compatible speech server with the **demo** backends (fixed-text STT, WAV-beep
 * TTS) so the endpoints answer immediately with no model. Point any OpenAI-compatible
 * client at it; swap in real backends (see README) for actual transcription/synthesis.
 */
fun main(args: Array<String>) {
    val opts = args.toList().windowed(2, 2, partialWindows = true).associate {
        it.first().removePrefix("--") to it.getOrElse(1) { "" }
    }
    val port = opts["port"]?.toIntOrNull() ?: DEFAULT_PORT
    val host = opts["host"] ?: "127.0.0.1"

    val server = SpeechServer(FixedTextSttBackend(), BeepTtsBackend(), port = port, host = host).start()
    val base = "http://$host:${server.port}"
    println("agents-kt-speech-server (demo backends) listening on $base")
    println("  POST $base/v1/audio/transcriptions   (multipart: file + model)")
    println("  POST $base/v1/audio/speech           (json: input, voice, response_format)")
    println("Swap FixedTextSttBackend / BeepTtsBackend for real backends — see README. Ctrl-C to stop.")

    Runtime.getRuntime().addShutdownHook(Thread { server.stop(0) })
    Thread.currentThread().join()
}

private const val DEFAULT_PORT = 8000
