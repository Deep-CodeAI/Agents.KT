package agents_engine.content

/**
 * Closed mime type for [Content.Image]. Variants cover the modalities
 * the production providers (Anthropic Vision, OpenAI Vision, Ollama
 * multimodal models) accept today. Extend by adding a variant — string
 * mime types are intentionally not exposed.
 */
sealed interface ImageMime {
    /** RFC 7-style mime form, returned by adapters when serialising to the wire. */
    val wireMime: String

    object Png : ImageMime { override val wireMime: String = "image/png" }
    object Jpeg : ImageMime { override val wireMime: String = "image/jpeg" }
    object Gif : ImageMime { override val wireMime: String = "image/gif" }
    object Webp : ImageMime { override val wireMime: String = "image/webp" }
}
