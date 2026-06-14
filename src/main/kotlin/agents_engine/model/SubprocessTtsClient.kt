package agents_engine.model

import agents_engine.content.AudioMime
import agents_engine.content.BlobStore
import agents_engine.content.Content
import agents_engine.sandbox.ProcessSandbox
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * #4510 — local text-to-speech **without a server**: pipe text to an external TTS
 * **binary** through [ProcessSandbox] (stdin in, audio file out) and return the typed
 * [Content.Audio]. agents.kt orchestrates; the engine is the binary you point at
 * (e.g. `piper`). No listening port, no JVM TTS engine — the in-process / subprocess
 * counterpart to the outbound `QwenTtsClient`. Implements [TtsModelClient], so it
 * drops straight into the `speak` tool.
 *
 * The subprocess is **write-confined** to [workDir] (the OS sandbox blocks writes
 * elsewhere); [command] receives the call's text and the output path and returns the
 * argv. Text is also fed on stdin by default (the common `piper`-style shape). Set
 * [requireSandbox] to fail closed when no OS sandbox backend is present.
 *
 * ```kotlin
 * val tts = SubprocessTtsClient(blobStore) { _, out ->
 *     listOf("piper", "--model", "/voices/en.onnx", "--output_file", out.toString())
 * }
 * agent { tools { +speakTool(tts) } }   // local TTS, no port, engine stays external
 * ```
 */
class SubprocessTtsClient(
    private val blobStore: BlobStore,
    private val mime: AudioMime = AudioMime.Wav,
    private val workDir: Path = Files.createTempDirectory("agents-kt-tts"),
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val requireSandbox: Boolean = false,
    private val passTextOnStdin: Boolean = true,
    private val command: (text: String, outputPath: Path) -> List<String>,
) : TtsModelClient {

    override fun speak(text: String): Content.Audio {
        require(text.isNotBlank()) { "SubprocessTtsClient: text must not be blank." }
        Files.createDirectories(workDir)
        val output = Files.createTempFile(workDir, "tts-", ".${fileExtension(mime)}")
        try {
            val result = ProcessSandbox.forWritableRoots(listOf(workDir)).run(
                command(text, output),
                stdin = if (passTextOnStdin) text else null,
                timeout = timeout,
                requireSandbox = requireSandbox,
            )
            check(result.ok) {
                "SubprocessTtsClient: TTS command failed (exit ${result.exitCode}): ${result.stderr.trim()}"
            }
            val bytes = Files.readAllBytes(output)
            check(bytes.isNotEmpty()) { "SubprocessTtsClient: TTS command produced no audio at $output." }
            return Content.Audio(ref = blobStore.put(bytes, mime.wireMime), mime = mime)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun fileExtension(mime: AudioMime): String = when (mime) {
        AudioMime.Mp3 -> "mp3"
        AudioMime.Wav -> "wav"
        AudioMime.Flac -> "flac"
        AudioMime.Ogg -> "ogg"
    }

    private companion object {
        val DEFAULT_TIMEOUT: Duration = 60.seconds
    }
}
