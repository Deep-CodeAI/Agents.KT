package agents_engine.core

/**
 * #2883 (first slice, threaded by #2889) — the closed ABI a tool executor
 * uses to reach the outside world, every operation gated by the tool's
 * declared [ToolPolicy] **before** it happens. A denied operation throws
 * [ToolPolicyViolation] — the executor cannot do more than it declared.
 *
 * v1 surface: filesystem text I/O + environment variables. Subprocesses
 * stay with `processTool` (the Layer-2 sandbox path); blobs/clock and
 * ledger envelope recording land with the rest of #2883.
 */
interface ToolEnvironment {
    /** Read a file the policy's `read` globs allow. */
    fun readText(path: String): String

    /** Write a file the policy's `write` globs allow. */
    fun writeText(path: String, content: String)

    /** Read an environment variable the policy's `environment` allow-list names. */
    fun env(name: String): String?
}
