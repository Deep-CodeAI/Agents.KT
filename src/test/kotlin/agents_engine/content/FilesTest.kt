package agents_engine.content

import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files as NioFiles
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * `agents_engine.content.Files` — extension-based loader for typed
 * [Content] from disk. Pins:
 *
 * 1. Each known extension maps to the correct Content variant + mime.
 *    Covers all four image / four audio / three video / five document
 *    variants.
 * 2. Case-insensitive on the extension.
 * 3. Bytes round-trip through the BlobStore — same ContentRef.hash
 *    as a manual `store.put(bytes, mime)`.
 * 4. Unknown extensions: [load] throws with the offending path +
 *    extension in the message; [loadOrNull] returns null.
 * 5. No-extension paths: [load] throws too.
 * 6. `loadAll` propagates errors; `loadAllOrSkip` silently skips.
 * 7. `canonicalExtensionFor` is the inverse — every loadable Content
 *    has an extension it can be written back out to.
 */
class FilesTest {

    private fun writeFile(dir: Path, name: String, bytes: ByteArray): Path {
        val path = dir.resolve(name)
        NioFiles.write(path, bytes)
        return path
    }

    @Test
    fun `load PNG produces Content Image with Png mime`(@TempDir dir: Path) {
        val store = InMemoryBlobStore()
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        val path = writeFile(dir, "thing.png", pngBytes)
        val content = Files.load(path, store)
        assertIs<Content.Image>(content)
        assertEquals(ImageMime.Png, content.mime)
        assertEquals(computeContentHash(pngBytes), content.ref.hash, "bytes round-trip via store")
        assertEquals(pngBytes.size.toLong(), content.ref.sizeBytes)
    }

    @Test
    fun `load all image extensions map to the right ImageMime variant`(@TempDir dir: Path) {
        val store = InMemoryBlobStore()
        val cases = listOf(
            "img.jpg" to ImageMime.Jpeg,
            "img.jpeg" to ImageMime.Jpeg,
            "anim.gif" to ImageMime.Gif,
            "modern.webp" to ImageMime.Webp,
        )
        for ((filename, expectedMime) in cases) {
            val path = writeFile(dir, filename, byteArrayOf(1, 2, 3))
            val content = Files.load(path, store)
            assertIs<Content.Image>(content)
            assertEquals(expectedMime, content.mime, "wrong mime for $filename")
        }
    }

    @Test
    fun `load all audio extensions map correctly`(@TempDir dir: Path) {
        val store = InMemoryBlobStore()
        val cases = listOf(
            "a.mp3" to AudioMime.Mp3,
            "a.wav" to AudioMime.Wav,
            "a.flac" to AudioMime.Flac,
            "a.ogg" to AudioMime.Ogg,
        )
        for ((filename, expectedMime) in cases) {
            val path = writeFile(dir, filename, byteArrayOf(1, 2, 3))
            val content = Files.load(path, store)
            assertIs<Content.Audio>(content)
            assertEquals(expectedMime, content.mime)
        }
    }

    @Test
    fun `load all video extensions map correctly`(@TempDir dir: Path) {
        val store = InMemoryBlobStore()
        val cases = listOf(
            "v.mp4" to VideoMime.Mp4,
            "v.webm" to VideoMime.WebM,
            "v.mov" to VideoMime.Mov,
        )
        for ((filename, expectedMime) in cases) {
            val path = writeFile(dir, filename, byteArrayOf(1, 2, 3))
            val content = Files.load(path, store)
            assertIs<Content.Video>(content)
            assertEquals(expectedMime, content.mime)
        }
    }

    @Test
    fun `load all document extensions map correctly`(@TempDir dir: Path) {
        val store = InMemoryBlobStore()
        val cases = listOf(
            "spec.pdf" to DocMime.Pdf,
            "memo.docx" to DocMime.Docx,
            "notes.md" to DocMime.Markdown,
            "readme.markdown" to DocMime.Markdown,
            "page.html" to DocMime.Html,
            "fragment.htm" to DocMime.Html,
            "plain.txt" to DocMime.PlainText,
        )
        for ((filename, expectedMime) in cases) {
            val path = writeFile(dir, filename, "content".toByteArray())
            val content = Files.load(path, store)
            assertIs<Content.Document>(content)
            assertEquals(expectedMime, content.mime, "wrong mime for $filename")
        }
    }

    @Test
    fun `extension matching is case-insensitive`(@TempDir dir: Path) {
        val store = InMemoryBlobStore()
        val mixedCase = listOf("FOO.PNG", "Bar.JPg", "BAZ.PdF")
        for (filename in mixedCase) {
            val path = writeFile(dir, filename, byteArrayOf(0, 0, 0))
            val content = Files.load(path, store)
            // Just check we got something — exact mime tested elsewhere
            assertNotNull(content, "case-insensitive load failed for $filename")
        }
    }

    @Test
    fun `unknown extension throws UnknownExtensionException with path + extension in message`(@TempDir dir: Path) {
        val store = InMemoryBlobStore()
        val path = writeFile(dir, "thing.xyz", byteArrayOf(1, 2, 3))
        val ex = assertThrows<UnknownExtensionException> { Files.load(path, store) }
        val msg = ex.message ?: ""
        assertTrue("xyz" in msg, "error names the offending extension: $msg")
        assertTrue("thing.xyz" in msg, "error names the path: $msg")
    }

    @Test
    fun `no-extension path throws too`(@TempDir dir: Path) {
        val store = InMemoryBlobStore()
        val path = writeFile(dir, "no-extension-here", byteArrayOf(1))
        assertThrows<UnknownExtensionException> { Files.load(path, store) }
    }

    @Test
    fun `loadOrNull returns null on unknown extension (instead of throwing)`(@TempDir dir: Path) {
        val store = InMemoryBlobStore()
        val path = writeFile(dir, "thing.xyz", byteArrayOf(1, 2, 3))
        assertNull(Files.loadOrNull(path, store))
    }

    @Test
    fun `loadAll throws on first unknown extension`(@TempDir dir: Path) {
        val store = InMemoryBlobStore()
        val paths = listOf(
            writeFile(dir, "ok.png", byteArrayOf(1)),
            writeFile(dir, "bad.xyz", byteArrayOf(2)),
        )
        assertThrows<UnknownExtensionException> { Files.loadAll(paths, store) }
    }

    @Test
    fun `loadAllOrSkip silently skips unknown extensions`(@TempDir dir: Path) {
        val store = InMemoryBlobStore()
        val paths = listOf(
            writeFile(dir, "good.png", byteArrayOf(1, 2, 3)),
            writeFile(dir, "skip.xyz", byteArrayOf(4)),
            writeFile(dir, "ok.pdf", byteArrayOf(5, 6)),
        )
        val loaded = Files.loadAllOrSkip(paths, store)
        assertEquals(2, loaded.size, "only known extensions kept")
        assertIs<Content.Image>(loaded[0])
        assertIs<Content.Document>(loaded[1])
    }

    @Test
    fun `canonicalExtensionFor round-trips with load for every modality variant`(@TempDir dir: Path) {
        val store = InMemoryBlobStore()
        val variants = listOf(
            Content.Image(store.put(byteArrayOf(1), ImageMime.Png.wireMime), ImageMime.Png) to "png",
            Content.Image(store.put(byteArrayOf(1), ImageMime.Jpeg.wireMime), ImageMime.Jpeg) to "jpg",
            Content.Image(store.put(byteArrayOf(1), ImageMime.Gif.wireMime), ImageMime.Gif) to "gif",
            Content.Image(store.put(byteArrayOf(1), ImageMime.Webp.wireMime), ImageMime.Webp) to "webp",
            Content.Audio(store.put(byteArrayOf(1), AudioMime.Mp3.wireMime), AudioMime.Mp3) to "mp3",
            Content.Audio(store.put(byteArrayOf(1), AudioMime.Wav.wireMime), AudioMime.Wav) to "wav",
            Content.Audio(store.put(byteArrayOf(1), AudioMime.Flac.wireMime), AudioMime.Flac) to "flac",
            Content.Audio(store.put(byteArrayOf(1), AudioMime.Ogg.wireMime), AudioMime.Ogg) to "ogg",
            Content.Video(store.put(byteArrayOf(1), VideoMime.Mp4.wireMime), VideoMime.Mp4) to "mp4",
            Content.Video(store.put(byteArrayOf(1), VideoMime.WebM.wireMime), VideoMime.WebM) to "webm",
            Content.Video(store.put(byteArrayOf(1), VideoMime.Mov.wireMime), VideoMime.Mov) to "mov",
            Content.Document(store.put(byteArrayOf(1), DocMime.Pdf.wireMime), DocMime.Pdf) to "pdf",
            Content.Document(store.put(byteArrayOf(1), DocMime.Docx.wireMime), DocMime.Docx) to "docx",
            Content.Document(store.put(byteArrayOf(1), DocMime.Markdown.wireMime), DocMime.Markdown) to "md",
            Content.Document(store.put(byteArrayOf(1), DocMime.Html.wireMime), DocMime.Html) to "html",
            Content.Document(store.put(byteArrayOf(1), DocMime.PlainText.wireMime), DocMime.PlainText) to "txt",
            Content.Text("hello") to "txt",
        )
        for ((content, expectedExt) in variants) {
            assertEquals(expectedExt, Files.canonicalExtensionFor(content), "wrong extension for $content")
        }
    }

    @Test
    fun `knownExtensions set covers all the cases load handles`() {
        val ks = Files.knownExtensions
        // Spot-check: every modality has at least one extension
        assertTrue("png" in ks && "jpg" in ks)
        assertTrue("mp3" in ks && "ogg" in ks)
        assertTrue("mp4" in ks && "webm" in ks)
        assertTrue("pdf" in ks && "md" in ks && "txt" in ks)
    }
}
