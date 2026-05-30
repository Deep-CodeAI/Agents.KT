package agents_engine.content

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * `agents_engine/content/ContentRef.kt` — content-addressed blob reference
 * + [BlobStore] backend (#2467, part of the #2465 multimodal epic).
 *
 * **Why content-addressed:**
 *
 * 1. **Snapshot-safe.** `SessionSnapshot` (#2386 / #2754) serialises through
 *    plain JSON. Inlining image / audio / video bytes would explode the
 *    snapshot file and slow every resume. With refs, the snapshot stays
 *    small (just hash + size + mime); blobs live in the [BlobStore] and
 *    are addressable by hash across restarts.
 * 2. **Audit-safe.** Audit bridges record `ContentRef` (hash + size +
 *    modality) but never blob bytes. Audit logs stay compact and PII-safe
 *    by construction.
 * 3. **Deduplicated.** Two identical images produce identical refs — the
 *    store keeps one copy. Useful in eval suites where the same fixture
 *    image flows through many cases.
 *
 * **Hash algorithm:** SHA-256 hex. Matches the manifest hash used
 * elsewhere (#1912 permission manifest, #2754 restore guard) so the
 * audit story has a single hash family. Collisions are not a practical
 * concern.
 *
 * **Mime + size on the ref:** the mime travels with the ref so a
 * caller can introspect "what is this blob?" without dereferencing the
 * store. Size is convenience metadata for audit rows; trustworthy
 * because computed at `put` time from the actual byte count.
 */
data class ContentRef(
    /** SHA-256 hex of the blob bytes. Stable across processes and JVM versions. */
    val hash: String,
    /** Blob length in bytes. Audit-friendly; never the bytes themselves. */
    val sizeBytes: Long,
    /**
     * Wire-form mime ("image/png", "application/pdf", …). Pulled from the
     * corresponding [ImageMime] / [DocMime] / etc. when the ref is created
     * by a typed [Content] put; freeform when a caller constructs a ref
     * directly (e.g. ingesting an unknown blob from disk). Adapters that
     * round-trip through typed `Content` enforce the closed mime types at
     * that layer.
     */
    val wireMime: String,
)

/**
 * Persistence backend for content-addressed blobs.
 *
 * Implementations: [InMemoryBlobStore] for tests and single-JVM use,
 * [FileBlobStore] for on-disk persistence. Custom backends (S3, GCS,
 * an internal artifact registry) implement this interface and plug in
 * via dependency injection at agent construction.
 *
 * **Idempotency:** `put` is deterministic on byte content. Putting the
 * same bytes twice returns the same [ContentRef]; the second `put` is
 * a no-op on disk.
 */
interface BlobStore {
    /**
     * Store [bytes] under their SHA-256 hash. Returns the resulting
     * [ContentRef] carrying that hash, the byte length, and [wireMime].
     */
    fun put(bytes: ByteArray, wireMime: String): ContentRef

    /**
     * Look up the blob for [ref]. Returns `null` when the store has no
     * entry — callers handle the absence (re-fetch, fail closed, etc.).
     */
    fun get(ref: ContentRef): ByteArray?

    /**
     * Stream the blob for [ref] — for large payloads where loading the
     * full bytes into memory is wasteful. `null` when missing.
     */
    fun open(ref: ContentRef): InputStream?

    /** True if the store currently holds [ref]'s blob. */
    fun exists(ref: ContentRef): Boolean

    /** Remove the blob for [ref] from the store. Idempotent. */
    fun delete(ref: ContentRef)
}

/**
 * Compute the [ContentRef] hash for [bytes] without storing anything.
 * Useful when comparing two byte arrays without a [BlobStore] handy.
 */
fun computeContentHash(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return buildString(digest.size * 2) { for (b in digest) append("%02x".format(b)) }
}

/**
 * In-process [BlobStore] — tests + single-JVM agents that don't need
 * persistence across restarts. Backed by a `ConcurrentHashMap` keyed
 * by hash; bytes are stored as defensive copies on `put` and returned
 * as copies on `get` so consumer mutation can't corrupt the store.
 */
class InMemoryBlobStore : BlobStore {
    private val store = ConcurrentHashMap<String, Entry>()

    private data class Entry(val bytes: ByteArray, val wireMime: String)

    override fun put(bytes: ByteArray, wireMime: String): ContentRef {
        val hash = computeContentHash(bytes)
        store[hash] = Entry(bytes.copyOf(), wireMime)
        return ContentRef(hash = hash, sizeBytes = bytes.size.toLong(), wireMime = wireMime)
    }

    override fun get(ref: ContentRef): ByteArray? = store[ref.hash]?.bytes?.copyOf()

    override fun open(ref: ContentRef): InputStream? = get(ref)?.inputStream()

    override fun exists(ref: ContentRef): Boolean = store.containsKey(ref.hash)

    override fun delete(ref: ContentRef) {
        store.remove(ref.hash)
    }
}

/**
 * On-disk [BlobStore] — one file per blob, filename = hash. Survives
 * process restarts so refs in a persisted [SessionSnapshot]
 * dereference after a restart. Atomic via tmp + rename.
 *
 * Filename is the raw SHA-256 hex — hashes are filesystem-safe by
 * construction. No suffix is appended; mime travels on the ref, not
 * in the path. (Files can be tagged with extension by a deployer's
 * out-of-band tooling if needed.)
 *
 * Composes with the #2753 filename-hashing pattern from
 * `FileSnapshotStore` — both use hashes for filename safety. Here the
 * hash is intrinsic (SHA-256 of blob content); there it was derived
 * (SHA-256 of session id).
 */
class FileBlobStore(private val dir: Path) : BlobStore {
    init { Files.createDirectories(dir) }

    override fun put(bytes: ByteArray, wireMime: String): ContentRef {
        val hash = computeContentHash(bytes)
        val target = dir.resolve(hash)
        if (!Files.exists(target)) {
            val tmp = dir.resolve("$hash.tmp")
            Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        return ContentRef(hash = hash, sizeBytes = bytes.size.toLong(), wireMime = wireMime)
    }

    override fun get(ref: ContentRef): ByteArray? {
        val target = dir.resolve(ref.hash)
        return if (Files.exists(target)) Files.readAllBytes(target) else null
    }

    override fun open(ref: ContentRef): InputStream? {
        val target = dir.resolve(ref.hash)
        return if (Files.exists(target)) Files.newInputStream(target) else null
    }

    override fun exists(ref: ContentRef): Boolean = Files.exists(dir.resolve(ref.hash))

    override fun delete(ref: ContentRef) {
        Files.deleteIfExists(dir.resolve(ref.hash))
    }
}
