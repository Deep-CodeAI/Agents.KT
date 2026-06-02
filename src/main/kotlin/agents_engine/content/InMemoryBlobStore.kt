package agents_engine.content

import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

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
