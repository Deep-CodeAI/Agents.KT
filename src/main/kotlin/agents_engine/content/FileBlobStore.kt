package agents_engine.content

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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
            // #2871 — unique tmp filename so two threads writing the SAME
            // hash (rare but valid — same bytes hashed independently) can
            // never collide on the tmp file. Pre-#2871 used `$hash.tmp`
            // deterministically, which would race: thread A writes, thread
            // B truncates A's tmp mid-write, B renames its partial file.
            // The atomic rename still works — target is keyed on hash, so
            // the second rename is a same-bytes overwrite.
            val tmp = dir.resolve("$hash.${java.util.UUID.randomUUID()}.tmp")
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
