package agents_engine.core

/** A component whose state can be captured and restored. */
interface Snapshotable<S> {
    fun snapshot(): S
    fun restore(state: S)
}
