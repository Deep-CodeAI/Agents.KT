package agents_engine.core

import java.util.concurrent.ConcurrentHashMap

/** In-process store — tests and single-JVM resume. */
class InMemorySnapshotStore : SnapshotStore {
    private val map = ConcurrentHashMap<String, SessionSnapshot>()
    override fun save(key: String, snapshot: SessionSnapshot) { map[key] = snapshot }
    override fun load(key: String): SessionSnapshot? = map[key]
    override fun delete(key: String) { map.remove(key) }
}
