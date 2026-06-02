package agents_engine.content

import java.io.InputStream

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

    /**
     * #2871 — integrity check. Returns `true` when [ref] resolves to bytes
     * whose SHA-256 still equals [ref.hash]. Returns `false` when the
     * stored bytes don't match (corruption, mid-write crash that wasn't
     * fully atomic, truncation by an external tool) OR when the blob is
     * absent.
     *
     * Default implementation re-reads via [get] and rehashes. Backends
     * that can verify cheaper (e.g. an on-disk checksum sidecar) override.
     *
     * Use case: audit-time spot check on the snapshot/blob directory
     * before resuming, or as a periodic integrity scan in long-running
     * deployments. Not on the hot path of [get] — `verify` is opt-in by
     * the caller.
     */
    fun verify(ref: ContentRef): Boolean {
        val bytes = get(ref) ?: return false
        return computeContentHash(bytes) == ref.hash
    }
}
