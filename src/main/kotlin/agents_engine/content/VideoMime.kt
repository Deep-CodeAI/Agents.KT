package agents_engine.content

/** Closed mime type for [Content.Video]. */
sealed interface VideoMime {
    val wireMime: String

    object Mp4 : VideoMime { override val wireMime: String = "video/mp4" }
    object WebM : VideoMime { override val wireMime: String = "video/webm" }
    object Mov : VideoMime { override val wireMime: String = "video/quicktime" }
}
