package agents_engine.content

import java.nio.file.Files as NioFiles
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #2871 — regression coverage for the three blob/file hardening surfaces:
 *   1. `Files.load(maxBytes = …)` — oversized files throw before bytes
 *      land in memory.
 *   2. `BlobStore.verify(ref)` — re-hashes and returns false on corruption
 *      / truncation, true on intact, false on absence.
 *   3. `FileBlobStore.put(...)` — concurrent writes of the same hash use
 *      unique tmp filenames; no tmp collision in `dir`.
 */
class FilesBlobStoreHardeningTest {

    // ─── Files.load size cap ─────────────────────────────────────────

    @Test
    fun `Files load throws OversizedFileException when file exceeds maxBytes`() {
        val tmp = createTempDirectory("files-size-cap")
        val path = tmp.resolve("big.png")
        // 5 KB file; cap at 1 KB → expect throw before bytes are read.
        NioFiles.write(path, ByteArray(5 * 1024))

        val store = InMemoryBlobStore()
        val ex = assertFails { Files.load(path, store, maxBytes = 1024) }
        assertTrue(ex is OversizedFileException, "expected OversizedFileException, got ${ex::class.simpleName}")
        val ofe = ex as OversizedFileException
        assertEquals(path, ofe.path)
        assertEquals(5L * 1024, ofe.sizeBytes)
        assertEquals(1024L, ofe.maxBytes)
        // Diagnostic must name both the actual size and the cap.
        assertTrue(ex.message!!.contains("5120 bytes"), "message names actual size: ${ex.message}")
        assertTrue(ex.message!!.contains("max allowed is 1024"), "message names cap: ${ex.message}")
    }

    @Test
    fun `Files load succeeds when file is under the cap`() {
        val tmp = createTempDirectory("files-under-cap")
        val path = tmp.resolve("small.png")
        // 256-byte file; cap at 1 KB → expect success.
        // Use real PNG magic-byte header so future smarter detection still works.
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        NioFiles.write(path, pngHeader + ByteArray(248))

        val store = InMemoryBlobStore()
        val content = Files.load(path, store, maxBytes = 1024)
        assertTrue(content is Content.Image, "PNG path should load as Image")
        assertEquals(256L, (content as Content.Image).ref.sizeBytes)
    }

    @Test
    fun `Files load default cap is 20 MiB`() {
        // Sanity-pin the default so changing it in code requires a test update.
        assertEquals(20L * 1024 * 1024, Files.DEFAULT_MAX_BYTES)
    }

    // ─── BlobStore.verify default ────────────────────────────────────

    @Test
    fun `BlobStore verify returns true for intact entry`() {
        val store = InMemoryBlobStore()
        val ref = store.put("hello".toByteArray(), "text/plain")
        assertTrue(store.verify(ref), "freshly-put blob must verify")
    }

    @Test
    fun `BlobStore verify returns false when blob is absent`() {
        val store = InMemoryBlobStore()
        val ref = ContentRef(hash = "0".repeat(64), sizeBytes = 0, wireMime = "text/plain")
        assertFalse(store.verify(ref), "missing blob must not verify")
    }

    @Test
    fun `FileBlobStore verify detects truncation by external tool`() {
        val tmp = createTempDirectory("verify-truncation")
        val store = FileBlobStore(tmp)
        val original = "Some real content here".toByteArray()
        val ref = store.put(original, "text/plain")
        assertTrue(store.verify(ref), "fresh put verifies")

        // Simulate an external tool truncating the file. The stored bytes no
        // longer match the recorded hash; verify() must catch it.
        val target = tmp.resolve(ref.hash)
        NioFiles.write(target, "TRUNCATED".toByteArray())
        assertFalse(store.verify(ref), "truncated blob must NOT verify (silent-corruption guard)")
    }

    // ─── FileBlobStore unique tmp filenames ──────────────────────────

    @Test
    fun `FileBlobStore concurrent put of identical bytes does not collide on tmp`() {
        val tmp = createTempDirectory("concurrent-put")
        val store = FileBlobStore(tmp)

        // Two threads put the SAME bytes — same hash. Pre-#2871 they would
        // both write to `$hash.tmp`, race on truncate/rename. With the
        // unique-tmp fix each write goes to a per-attempt UUID'd tmp, so
        // both renames succeed (or one finds the target already exists)
        // and no tmp file leaks in the directory.
        val payload = ("seed for concurrent put — " + "A".repeat(1024)).toByteArray()
        val expectedHash = computeContentHash(payload)
        val threads = 16
        val barrier = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val refs = java.util.concurrent.ConcurrentLinkedQueue<ContentRef>()
        repeat(threads) {
            pool.submit {
                try {
                    barrier.await()
                    refs.add(store.put(payload, "text/plain"))
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        barrier.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "concurrent puts did not finish in time")

        // No errors raised by any thread.
        if (errors.isNotEmpty()) fail("Threads threw: ${errors.joinToString { it.message ?: it::class.simpleName ?: "?" }}")
        // All N refs point at the same hash.
        assertEquals(threads, refs.size)
        assertTrue(refs.all { it.hash == expectedHash }, "all refs must share the deterministic hash")
        // No leftover `.tmp` files in the dir — every UUID'd tmp got renamed
        // or cleaned up. (The unique-tmp design accepts that a tmp may
        // briefly co-exist with the final file; this check runs AFTER all
        // threads have completed, so the steady state must be tmp-free.)
        val leftovers = NioFiles.list(tmp).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tmp") }.toList()
        }
        assertTrue(leftovers.isEmpty(), "no leftover .tmp files after concurrent puts: $leftovers")
        // Final file exists and verifies.
        val finalRef = refs.first()
        assertNotNull(store.get(finalRef))
        assertTrue(store.verify(finalRef))
    }
}
