package agents_engine.content

import java.security.MessageDigest

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
 * Compute the [ContentRef] hash for [bytes] without storing anything.
 * Useful when comparing two byte arrays without a [BlobStore] handy.
 */
fun computeContentHash(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return buildString(digest.size * 2) { for (b in digest) append("%02x".format(b)) }
}
