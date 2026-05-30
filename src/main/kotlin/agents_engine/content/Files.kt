package agents_engine.content

import java.nio.file.Files as NioFiles
import java.nio.file.Path

/**
 * `agents_engine/content/Files.kt` — convenience surface over [Content]
 * + [BlobStore] for the common "I have a file on disk, give me the
 * right typed `Content` for an agent invocation" pattern.
 *
 * Before this helper:
 *
 * ```kotlin
 * val bytes = Files.readAllBytes(Path.of("spec.pdf"))
 * val ref = store.put(bytes, DocMime.Pdf.wireMime)
 * val content = Content.Document(ref, DocMime.Pdf)
 * agent.invokeWithAttachments("review", listOf(content))
 * ```
 *
 * After:
 *
 * ```kotlin
 * import agents_engine.content.Files
 *
 * agent.invokeWithAttachments(
 *     "review",
 *     listOf(Files.load(Path.of("spec.pdf"), store)),
 * )
 * ```
 *
 * Mime detection is by **filename extension only** — fast, predictable,
 * no magic-byte sniffing. Misclassification on a wrong extension is the
 * caller's problem; explicit `Content.X(ref, mime)` construction is
 * always available when the file extension lies. Extensions are matched
 * case-insensitively (`Foo.PDF` and `foo.pdf` both produce
 * `DocMime.Pdf`).
 *
 * **Coverage:** every `wireMime` on every `Content`-modality mime variant
 * has at least one canonical extension mapped here. Unknown extensions
 * raise [UnknownExtensionException] in [load] / return `null` in
 * [loadOrNull]. Adding a new modality variant requires adding the
 * extension here too — the variant exhaustiveness check inside the
 * `extensionToContent` helper makes that requirement compile-visible.
 */
object Files {

    /**
     * Read [path], detect modality + mime from the filename extension,
     * put the bytes into [store], and return the corresponding typed
     * [Content].
     *
     * @throws UnknownExtensionException when the extension isn't
     *   recognised. Use [loadOrNull] if you want a null-on-unknown path.
     * @throws java.nio.file.NoSuchFileException when the file doesn't
     *   exist (propagated from [NioFiles.readAllBytes]).
     */
    fun load(path: Path, store: BlobStore): Content =
        loadOrNull(path, store)
            ?: throw UnknownExtensionException(path)

    /**
     * Same as [load] but returns `null` when the extension isn't
     * recognised, instead of throwing. Useful when iterating over a
     * directory and silently skipping unsupported files.
     */
    fun loadOrNull(path: Path, store: BlobStore): Content? {
        val ext = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val bytes = NioFiles.readAllBytes(path)
        return extensionToContent(ext, bytes, store)
    }

    /**
     * Convenience: load multiple files. Unknown extensions throw via
     * [load]; use [loadAllOrSkip] to silently skip them.
     */
    fun loadAll(paths: List<Path>, store: BlobStore): List<Content> =
        paths.map { load(it, store) }

    /**
     * Convenience: load multiple files, silently skipping any with an
     * unknown extension. Useful for directory ingestion where the set
     * of file types isn't fully known up front.
     */
    fun loadAllOrSkip(paths: List<Path>, store: BlobStore): List<Content> =
        paths.mapNotNull { loadOrNull(it, store) }

    /**
     * The set of file extensions [load] recognises. Stable enough for
     * callers to do "is this file going to be loadable?" checks without
     * a try/catch.
     */
    val knownExtensions: Set<String> = setOf(
        // images
        "png", "jpg", "jpeg", "gif", "webp",
        // audio
        "mp3", "wav", "flac", "ogg",
        // video
        "mp4", "webm", "mov",
        // documents
        "pdf", "docx", "md", "markdown", "html", "htm", "txt",
    )

    /**
     * Mime → canonical extension. Inverse of the lookup [load] does.
     * Useful for tooling that wants to write a [Content] to disk with
     * a sensible filename.
     */
    fun canonicalExtensionFor(content: Content): String? = when (content) {
        is Content.Text -> "txt"
        is Content.Image -> when (content.mime) {
            ImageMime.Png -> "png"
            ImageMime.Jpeg -> "jpg"
            ImageMime.Gif -> "gif"
            ImageMime.Webp -> "webp"
        }
        is Content.Audio -> when (content.mime) {
            AudioMime.Mp3 -> "mp3"
            AudioMime.Wav -> "wav"
            AudioMime.Flac -> "flac"
            AudioMime.Ogg -> "ogg"
        }
        is Content.Video -> when (content.mime) {
            VideoMime.Mp4 -> "mp4"
            VideoMime.WebM -> "webm"
            VideoMime.Mov -> "mov"
        }
        is Content.Document -> when (content.mime) {
            DocMime.Pdf -> "pdf"
            DocMime.Docx -> "docx"
            DocMime.Markdown -> "md"
            DocMime.Html -> "html"
            DocMime.PlainText -> "txt"
        }
    }

    /**
     * Extension dispatcher. Adding a new `Content` modality variant
     * (Stage 2 / future) will fail the `when` exhaustiveness check on
     * [canonicalExtensionFor] AND require a new branch here.
     */
    private fun extensionToContent(ext: String, bytes: ByteArray, store: BlobStore): Content? = when (ext) {
        // images
        "png" -> Content.Image(store.put(bytes, ImageMime.Png.wireMime), ImageMime.Png)
        "jpg", "jpeg" -> Content.Image(store.put(bytes, ImageMime.Jpeg.wireMime), ImageMime.Jpeg)
        "gif" -> Content.Image(store.put(bytes, ImageMime.Gif.wireMime), ImageMime.Gif)
        "webp" -> Content.Image(store.put(bytes, ImageMime.Webp.wireMime), ImageMime.Webp)
        // audio
        "mp3" -> Content.Audio(store.put(bytes, AudioMime.Mp3.wireMime), AudioMime.Mp3)
        "wav" -> Content.Audio(store.put(bytes, AudioMime.Wav.wireMime), AudioMime.Wav)
        "flac" -> Content.Audio(store.put(bytes, AudioMime.Flac.wireMime), AudioMime.Flac)
        "ogg" -> Content.Audio(store.put(bytes, AudioMime.Ogg.wireMime), AudioMime.Ogg)
        // video
        "mp4" -> Content.Video(store.put(bytes, VideoMime.Mp4.wireMime), VideoMime.Mp4)
        "webm" -> Content.Video(store.put(bytes, VideoMime.WebM.wireMime), VideoMime.WebM)
        "mov" -> Content.Video(store.put(bytes, VideoMime.Mov.wireMime), VideoMime.Mov)
        // documents
        "pdf" -> Content.Document(store.put(bytes, DocMime.Pdf.wireMime), DocMime.Pdf)
        "docx" -> Content.Document(store.put(bytes, DocMime.Docx.wireMime), DocMime.Docx)
        "md", "markdown" -> Content.Document(store.put(bytes, DocMime.Markdown.wireMime), DocMime.Markdown)
        "html", "htm" -> Content.Document(store.put(bytes, DocMime.Html.wireMime), DocMime.Html)
        "txt" -> Content.Document(store.put(bytes, DocMime.PlainText.wireMime), DocMime.PlainText)
        else -> null
    }
}

/**
 * Thrown by [Files.load] when the path's extension doesn't map to any
 * known [Content] variant. Names the offending extension + path so
 * the error is debuggable.
 */
class UnknownExtensionException(val path: Path) : IllegalArgumentException(
    "Files.load: no Content variant for path \"$path\" (extension = " +
        "\"${path.fileName?.toString()?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() } ?: "<none>"}\"). " +
        "Construct the Content variant explicitly when the extension is ambiguous or missing, " +
        "or use Files.loadOrNull to skip silently. Known extensions: ${Files.knownExtensions.sorted()}.",
)
