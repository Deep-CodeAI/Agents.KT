package agents_engine.content

/** Closed mime type for [Content.Audio]. */
sealed interface AudioMime {
    val wireMime: String

    object Mp3 : AudioMime { override val wireMime: String = "audio/mpeg" }
    object Wav : AudioMime { override val wireMime: String = "audio/wav" }
    object Flac : AudioMime { override val wireMime: String = "audio/flac" }
    object Ogg : AudioMime { override val wireMime: String = "audio/ogg" }
}
