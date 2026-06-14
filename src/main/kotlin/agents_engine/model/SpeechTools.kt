package agents_engine.model

import agents_engine.content.BlobStore
import agents_engine.content.Content
import agents_engine.content.Files
import agents_engine.content.ToolResult
import agents_engine.core.ToolRisk
import agents_engine.core.toolPolicy
import java.nio.file.Path

/**
 * #4501 — multimodal **as tools**. Rather than wiring audio through the attachment
 * path, expose speech-to-text and text-to-speech as built-in [ToolDef]s the model
 * orchestrates itself — reusing the full tool spine (ToolPolicy + Layer-1
 * filesystem gate, constraints, audit, manifest, typed hooks). The clients are the
 * [SpeechToTextClient] / [TtsModelClient] interfaces, so any adapter plugs in —
 * self-hosted ([WhisperSttClient] / [QwenTtsClient]) or hosted (OpenAI).
 *
 * ```kotlin
 * val blobs = InMemoryBlobStore()
 * agent<String, String>("voicebot") {
 *     model { ollama("llama3.1") }
 *     tools {
 *         +transcribeAudioTool(WhisperSttClient("http://localhost:8000"), blobs, audioRoot = "/recordings")
 *         +speakTool(QwenTtsClient("http://localhost:8880", blobs, voice = "Cherry"))
 *     }
 *     skills { skill<String, String>("assist", "Hears and answers") { tools("transcribe_audio", "speak") } }
 * }
 * ```
 */

/** JSON Schema for the model — without it the provider sees a no-arg tool and can't pass the path. */
private const val TRANSCRIBE_SCHEMA =
    """{"type":"object","properties":{"path":{"type":"string",""" +
        """"description":"Filesystem path to the audio file (mp3/wav/flac/ogg) to transcribe."}},""" +
        """"required":["path"],"additionalProperties":false}"""

private const val SPEAK_SCHEMA =
    """{"type":"object","properties":{"text":{"type":"string",""" +
        """"description":"The text to synthesize into speech."}},""" +
        """"required":["text"],"additionalProperties":false}"""

/**
 * Tool `transcribe_audio(path)` — loads a local audio file (confined to [audioRoot]
 * by the declared filesystem-read policy and a defensive prefix check) into
 * [blobStore], transcribes it via [stt], and returns the transcript text. Declares
 * `network` egress (the STT client ships the bytes to its endpoint).
 */
fun transcribeAudioTool(
    stt: SpeechToTextClient,
    blobStore: BlobStore,
    audioRoot: String,
): ToolDef {
    val root = Path.of(audioRoot).toAbsolutePath().normalize()
    val policy = toolPolicy {
        risk = ToolRisk.MEDIUM
        filesystem { read("$root/**") }
        network { allowAll() }
    }
    return ToolDef(
        name = "transcribe_audio",
        description = "Transcribe a local audio file (mp3/wav/flac/ogg) to text. " +
            "Args: path (string) — the audio file under the allowed audio directory.",
        parametersSchemaJson = TRANSCRIBE_SCHEMA,
        risk = policy.risk,
        policy = policy,
    ) { args ->
        val pathArg = args["path"]?.toString()
            ?: error("transcribe_audio requires a 'path' argument.")
        val path = Path.of(pathArg).toAbsolutePath().normalize()
        require(path.startsWith(root)) {
            "transcribe_audio: '$pathArg' is outside the allowed audio directory '$root'."
        }
        val content = Files.load(path, blobStore)
        val audio = content as? Content.Audio
            ?: error("transcribe_audio: '$pathArg' is not an audio file (got ${content::class.simpleName}).")
        stt.transcribe(audio, blobStore)
    }
}

/**
 * Tool `speak(text)` — synthesizes speech from [text] via [tts] and returns a
 * [ToolResult] carrying a text confirmation plus the typed [Content.Audio]
 * (bytes in the client's BlobStore, ref travels through audit + snapshot).
 * Declares `network` egress.
 */
fun speakTool(tts: TtsModelClient): ToolDef {
    val policy = toolPolicy {
        risk = ToolRisk.MEDIUM
        network { allowAll() }
    }
    return ToolDef(
        name = "speak",
        description = "Synthesize speech audio from text. Args: text (string). " +
            "Returns a confirmation plus the generated audio as a typed attachment.",
        parametersSchemaJson = SPEAK_SCHEMA,
        risk = policy.risk,
        policy = policy,
    ) { args ->
        val text = args["text"]?.toString()?.takeIf { it.isNotBlank() }
            ?: error("speak requires a non-blank 'text' argument.")
        val audio = tts.speak(text)
        ToolResult(
            Content.Text("Synthesized ${audio.ref.sizeBytes} bytes of ${audio.mime.wireMime} audio."),
            audio,
        )
    }
}

/**
 * Convenience bundle: both speech tools wired to the same clients/store. Register
 * with `tools { speechTools(stt, tts, blobs, audioRoot).forEach { +it } }`.
 */
fun speechTools(
    stt: SpeechToTextClient,
    tts: TtsModelClient,
    blobStore: BlobStore,
    audioRoot: String,
): List<ToolDef> = listOf(
    transcribeAudioTool(stt, blobStore, audioRoot),
    speakTool(tts),
)
