package agents_engine.model

import agents_engine.content.Content

/**
 * #3867.c — typed text-to-speech: text in, [Content.Audio] out (bytes in
 * the caller's `BlobStore`, typed ref travels). Ship: [OpenAiTtsClient].
 */
fun interface TtsModelClient {
    fun speak(text: String): Content.Audio
}
