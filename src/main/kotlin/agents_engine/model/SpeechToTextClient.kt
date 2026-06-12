package agents_engine.model

import agents_engine.content.BlobStore
import agents_engine.content.Content

/**
 * #3867.a — speech-to-text over a typed [Content.Audio]. Implementations
 * resolve the audio bytes through the [BlobStore] the ref came from and
 * return the transcript. Ship: [OpenAiSpeechToTextClient] (Whisper).
 */
fun interface SpeechToTextClient {
    fun transcribe(audio: Content.Audio, blobStore: BlobStore): String
}
