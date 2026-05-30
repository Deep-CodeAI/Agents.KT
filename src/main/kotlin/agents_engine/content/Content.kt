package agents_engine.content

/**
 * `agents_engine/content/Content.kt` — typed multimodal content hierarchy
 * (#2466, part of the #2465 0.8 multimodal epic).
 *
 * **Design hinges:**
 *
 * 1. **Modality + format live in the type, never in a String.** `Content` is
 *    a sealed interface; each non-text variant carries a `ContentRef` plus a
 *    closed (sealed-or-enum) mime type. No `mimeType: String` appears in any
 *    public API.
 * 2. **Content-addressed payload, not inlined bytes.** Non-text variants
 *    hold a [ContentRef], not a `ByteArray`. The actual bytes live in a
 *    [BlobStore]. This keeps `Content` immutable, equatable, snapshot-safe
 *    (the #2386 / #2754 snapshot machinery never inlines blobs), and audit-
 *    safe (audit rows record refs + modalities, never blob contents).
 * 3. **No `data class` with `ByteArray`.** Kotlin data-class equals/hashCode
 *    use the object identity of arrays, not their content. That breaks
 *    every assumption a downstream consumer would make about
 *    `content1 == content2`. The ref pattern sidesteps it entirely.
 *
 * **Staging (per the #2465 epic):**
 *
 * - Stage 1 (this commit): all five variants modelled. Image + Document
 *   are the modalities wired through the rest of the stack in 0.8 — they
 *   match the spec → product loop the runtime actually serves (spec
 *   ingestion, screenshot/UI-QA, architecture-diagram review).
 * - Stage 2: Audio + Video exercised end-to-end when a concrete use
 *   case lands.
 *
 * **Composition with existing surfaces:**
 *
 * - Tools can return [Content] inside a [ToolResult]; audit bridges
 *   record modalities + refs.
 * - Snapshot/resume holds refs (not bytes), so a snapshot file stays
 *   small regardless of how much image/audio/video the agent processed.
 * - Provider adapters (sibling #2470, deferred) translate `Content →
 *   provider-specific payload` at the wire.
 */
sealed interface Content {
    /**
     * Plain text content. The one variant that holds its payload inline,
     * because text is small, structural, and the lingua franca of LLM
     * messages. Stays unchanged from the pre-#2466 string-only world.
     */
    data class Text(val text: String) : Content

    /**
     * An image. [ref] points at the bytes in a [BlobStore]; [mime] is a
     * typed [ImageMime] (never a `String`). Use this for screenshots,
     * UI captures, architecture diagrams, photographs.
     */
    data class Image(val ref: ContentRef, val mime: ImageMime) : Content

    /**
     * Audio — speech, ambient capture, telephony record. Modelled in
     * Stage 1 but only wired end-to-end through provider adapters in
     * Stage 2.
     */
    data class Audio(val ref: ContentRef, val mime: AudioMime) : Content

    /**
     * Video. Same Stage 1/2 split as [Audio] — type ships now, provider
     * rendering ships when a concrete use case lands.
     */
    data class Video(val ref: ContentRef, val mime: VideoMime) : Content

    /**
     * A document — PDF, DOCX, Markdown. The other modality the 0.8
     * spec → product loop consumes (spec ingestion, regulatory PDFs).
     */
    data class Document(val ref: ContentRef, val mime: DocMime) : Content
}

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

/** Closed mime type for [Content.Audio]. */
sealed interface AudioMime {
    val wireMime: String

    object Mp3 : AudioMime { override val wireMime: String = "audio/mpeg" }
    object Wav : AudioMime { override val wireMime: String = "audio/wav" }
    object Flac : AudioMime { override val wireMime: String = "audio/flac" }
    object Ogg : AudioMime { override val wireMime: String = "audio/ogg" }
}

/** Closed mime type for [Content.Video]. */
sealed interface VideoMime {
    val wireMime: String

    object Mp4 : VideoMime { override val wireMime: String = "video/mp4" }
    object WebM : VideoMime { override val wireMime: String = "video/webm" }
    object Mov : VideoMime { override val wireMime: String = "video/quicktime" }
}

/** Closed mime type for [Content.Document]. */
sealed interface DocMime {
    val wireMime: String

    object Pdf : DocMime { override val wireMime: String = "application/pdf" }
    object Docx : DocMime {
        override val wireMime: String =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
    object Markdown : DocMime { override val wireMime: String = "text/markdown" }
    object Html : DocMime { override val wireMime: String = "text/html" }
    object PlainText : DocMime { override val wireMime: String = "text/plain" }
}

/**
 * The runtime-stable name of a content's modality. Used by audit
 * bridges to write a per-part `modality` field without exposing
 * type-checker concerns. Stable across releases — adding a new
 * [Content] variant adds a new modality string here, never repurposes
 * an existing one.
 */
val Content.modality: String
    get() = when (this) {
        is Content.Text -> "text"
        is Content.Image -> "image"
        is Content.Audio -> "audio"
        is Content.Video -> "video"
        is Content.Document -> "document"
    }
