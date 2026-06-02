package agents_engine.core

/**
 * #2754 — thrown by the resume path when a snapshot's `manifestHash` does not
 * match the resuming agent's current `manifestHash`. Fail-closed by default;
 * callers who own the migration story can opt out via the resume seam's
 * `allowManifestMismatch = true` flag.
 *
 * For an audit-first runtime this is the right default: a snapshot taken
 * under one tool/permission set must not silently replay against an agent
 * whose manifest has since changed (tools added, policies tightened, secrets
 * rotated, etc.). Better to refuse than to widen authority by accident.
 */
class SnapshotManifestMismatchException(
    val expected: String?,
    val actual: String?,
) : RuntimeException(
    "Cannot resume: snapshot manifestHash=$expected does not match current agent manifestHash=$actual. " +
        "If you own the migration semantics, pass allowManifestMismatch = true to the resume seam.",
)
