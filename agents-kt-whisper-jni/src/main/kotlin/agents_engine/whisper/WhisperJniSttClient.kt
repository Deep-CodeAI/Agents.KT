package agents_engine.whisper

import agents_engine.content.AudioMime
import agents_engine.content.BlobStore
import agents_engine.content.Content
import agents_engine.model.SpeechToTextClient

/**
 * #4505 — in-process Whisper STT: a [SpeechToTextClient] with **no server**. It
 * resolves the audio bytes from the [BlobStore], decodes the WAV to mono float PCM
 * on the JVM, and hands the samples to a [WhisperBackend] (whisper.cpp via JNI).
 *
 * This module ships no native artifact and no weights: provision the model file with
 * [WhisperModelResolver] and supply a [backend] over the whisper.cpp JNI lib (see the
 * module README for the binding). Drop-in anywhere a `SpeechToTextClient` is expected
 * — including the `transcribe_audio` tool — so swapping the hosted Whisper for a
 * local one is a one-line change.
 *
 * Input must be WAV PCM (whisper.cpp's native shape); convert other formats first
 * (`ffmpeg -ar 16000 -ac 1 -c:a pcm_s16le out.wav`).
 */
class WhisperJniSttClient(
    private val backend: WhisperBackend,
) : SpeechToTextClient {

    override fun transcribe(audio: Content.Audio, blobStore: BlobStore): String {
        require(audio.mime == AudioMime.Wav) {
            "WhisperJniSttClient decodes WAV PCM; got ${audio.mime.wireMime}. " +
                "Convert first: `ffmpeg -ar 16000 -ac 1 -c:a pcm_s16le out.wav`."
        }
        val bytes = blobStore.get(audio.ref)
            ?: error("Audio ref ${audio.ref.hash} not found in the supplied BlobStore.")
        val (samples, sampleRate) = decodeWavToMonoFloat(bytes)
        return backend.transcribe(samples, sampleRate)
    }
}
