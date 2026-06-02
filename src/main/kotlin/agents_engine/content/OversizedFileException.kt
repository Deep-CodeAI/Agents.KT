package agents_engine.content

import java.nio.file.Path

/**
 * #2871 — thrown by [Files.load] / [Files.loadOrNull] / [Files.loadAll] /
 * [Files.loadAllOrSkip] when a file exceeds the per-call `maxBytes` cap.
 * Names the path, the actual size, AND the cap so the diagnostic points
 * at both the input and the configured guardrail.
 *
 * The check is performed via `Files.size(path)` *before* the bytes are
 * read into memory — an oversized 4 GiB upload throws cleanly instead
 * of OOMing the JVM.
 */
class OversizedFileException(
    val path: Path,
    val sizeBytes: Long,
    val maxBytes: Long,
) : IllegalArgumentException(
    "Files.load: \"$path\" is $sizeBytes bytes; max allowed is $maxBytes bytes. " +
        "Pass a higher `maxBytes` if this is intentional, or pre-filter the path list.",
)
