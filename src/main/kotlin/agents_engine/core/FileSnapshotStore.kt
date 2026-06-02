package agents_engine.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * On-disk store: one JSON file per key. Writes go to a temp file then
 * atomic-rename, so a crash mid-write can never corrupt the live snapshot —
 * you lose at most the in-flight write, keeping the last good one.
 *
 * #2753 — keys are hashed (SHA-256 hex) before becoming filenames. A key
 * like `"../../../etc/passwd"` or `"foo/bar\n*"` is filesystem-safe by
 * construction; the raw session id is still preserved inside the snapshot
 * body (`requestId` / `sessionId` fields) for traceability.
 */
class FileSnapshotStore(private val dir: Path) : SnapshotStore {
    override fun save(key: String, snapshot: SessionSnapshot) {
        Files.createDirectories(dir)
        val name = safeName(key)
        val target = dir.resolve("$name.json")
        val tmp = dir.resolve("$name.json.tmp")
        Files.writeString(tmp, SnapshotJson.encode(snapshot))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun load(key: String): SessionSnapshot? {
        val target = dir.resolve("${safeName(key)}.json")
        return if (Files.exists(target)) SnapshotJson.decode(Files.readString(target)) else null
    }

    override fun delete(key: String) { Files.deleteIfExists(dir.resolve("${safeName(key)}.json")) }

    private fun safeName(key: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) { for (b in bytes) append("%02x".format(b)) }
    }
}
