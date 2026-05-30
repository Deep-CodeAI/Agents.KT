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
     * #2871 — default size cap for [load] / [loadOrNull]. 20 MiB is large
     * enough for typical document / image attachments (a high-res PNG, a
     * 200-page PDF) but small enough to fail-fast on an accidental
     * upload of a 4 GB video or log dump before the JVM tries to slurp
     * the bytes into memory. Override per-call with the `maxBytes` arg.
     */
    const val DEFAULT_MAX_BYTES: Long = 20L * 1024 * 1024

    /**
     * Read [path], detect modality + mime from the filename extension,
     * put the bytes into [store], and return the corresponding typed
     * [Content].
     *
     * @param maxBytes hard cap on the file size in bytes (#2871). Default
     *   [DEFAULT_MAX_BYTES] = 20 MiB. Files larger than the cap throw
     *   [OversizedFileException] without reading the bytes into memory.
     *
     * @throws UnknownExtensionException when the extension isn't
     *   recognised. Use [loadOrNull] if you want a null-on-unknown path.
     * @throws OversizedFileException when the file exceeds [maxBytes].
     * @throws java.nio.file.NoSuchFileException when the file doesn't
     *   exist (propagated from [NioFiles.readAllBytes]).
     */
    fun load(path: Path, store: BlobStore, maxBytes: Long = DEFAULT_MAX_BYTES): Content =
        loadOrNull(path, store, maxBytes)
            ?: throw UnknownExtensionException(path)

    /**
     * Same as [load] but returns `null` when the extension isn't
     * recognised, instead of throwing. Useful when iterating over a
     * directory and silently skipping unsupported files.
     *
     * @param maxBytes hard cap on the file size in bytes (#2871). Default
     *   [DEFAULT_MAX_BYTES] = 20 MiB. An oversized file still throws
     *   [OversizedFileException] — [maxBytes] is a fail-fast safety
     *   check, not a soft predicate.
     */
    fun loadOrNull(path: Path, store: BlobStore, maxBytes: Long = DEFAULT_MAX_BYTES): Content? {
        val ext = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        // #2871 — size-check via syscall before reading. NoSuchFileException
        // propagates from Files.size like it did from readAllBytes.
        val size = NioFiles.size(path)
        if (size > maxBytes) throw OversizedFileException(path, size, maxBytes)
        val bytes = NioFiles.readAllBytes(path)
        return extensionToContent(ext, bytes, store)
    }

    /**
     * Convenience: load multiple files. Unknown extensions throw via
     * [load]; use [loadAllOrSkip] to silently skip them.
     */
    fun loadAll(paths: List<Path>, store: BlobStore, maxBytes: Long = DEFAULT_MAX_BYTES): List<Content> =
        paths.map { load(it, store, maxBytes) }

    /**
     * Convenience: load multiple files, silently skipping any with an
     * unknown extension. Useful for directory ingestion where the set
     * of file types isn't fully known up front. Oversize is NOT silently
     * skipped — it still throws [OversizedFileException] (use a smaller
     * [maxBytes] or pre-filter `paths` if you need lenient behaviour).
     */
    fun loadAllOrSkip(paths: List<Path>, store: BlobStore, maxBytes: Long = DEFAULT_MAX_BYTES): List<Content> =
        paths.mapNotNull { loadOrNull(it, store, maxBytes) }

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

/**
 * #2871 — thrown by [Files.load] / [Files.loadOrNull] / [Files.loadAll] /
 * [Files.loadAllOrSkip] when a file exceeds the per-call `maxBytes` cap.
 * Names the path, the actual size, AND the cap so the diagnostic points
 * at both the input and the configured guardrail.
 *
 * The check is performed via `Files.size(path)` *before* the bytes are
 * read into memory — an oversized 4 GiB upload throws cleanly instead
 * of OOMing the JVM.
 */
class OversizedFileException(
    val path: Path,
    val sizeBytes: Long,
    val maxBytes: Long,
) : IllegalArgumentException(
    "Files.load: \"$path\" is $sizeBytes bytes; max allowed is $maxBytes bytes. " +
        "Pass a higher `maxBytes` if this is intentional, or pre-filter the path list.",
)
