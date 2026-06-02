package agents_engine.core

/** Persistence backend for [SessionSnapshot], keyed by session id. */
interface SnapshotStore {
    fun save(key: String, snapshot: SessionSnapshot)
    fun load(key: String): SessionSnapshot?
    fun delete(key: String)
}
