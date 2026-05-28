package agents_engine.core

import java.util.concurrent.ConcurrentHashMap

/**
 * #2420 / #2386 — Phase 2c. Composition snapshots track the *scaffolding*
 * between leaf agents: which stage of a Pipeline is the next to run, the
 * intermediate value passed between stages, which iteration of a Loop is
 * pending, which Branch was taken, and so on. Distinct from
 * [SessionSnapshot], which captures the message-history-and-counters state
 * of one agentic loop inside a leaf agent. The two can coexist: a Pipeline
 * stage that is itself a persistent Agent will have BOTH a
 * [CompositionSnapshot] (recording its stage index in the outer pipeline)
 * AND a [SessionSnapshot] (recording its own conversation), each in its
 * own store.
 *
 * v1 (this ticket) limits the [intermediate] field to a `String` so the
 * snapshot has a wire-stable representation without needing the
 * `@Generable` round-trip path. Typed-intermediate snapshots are tracked
 * separately on #2386.
 */
data class CompositionSnapshot(
    /** Session key the caller passed to `resumeOrStart`. */
    val sessionId: String,
    /**
     * Number of stages already completed in the composition. On resume,
     * stages with index `< stageIndex` are skipped. `0` for a fresh run
     * (equivalent to "nothing saved yet").
     */
    val stageIndex: Int,
    /**
     * The value handed to stage `stageIndex` (i.e., the output of the
     * last completed stage). Stored as a `String` in v1; typed encodings
     * land in a follow-up.
     */
    val intermediate: String,
)

/**
 * #2420 — persistence backend for [CompositionSnapshot], keyed by session id.
 * Sibling to [SnapshotStore]; named separately so the two snapshot types
 * cannot be cross-wired by accident (a Pipeline snapshot is not a session
 * snapshot, and vice versa).
 */
interface CompositionSnapshotStore {
    fun save(key: String, snapshot: CompositionSnapshot)
    fun load(key: String): CompositionSnapshot?
    fun delete(key: String)
}

/** In-process [CompositionSnapshotStore] — tests and single-JVM resume. */
class InMemoryCompositionSnapshotStore : CompositionSnapshotStore {
    private val map = ConcurrentHashMap<String, CompositionSnapshot>()
    override fun save(key: String, snapshot: CompositionSnapshot) { map[key] = snapshot }
    override fun load(key: String): CompositionSnapshot? = map[key]
    override fun delete(key: String) { map.remove(key) }
}
