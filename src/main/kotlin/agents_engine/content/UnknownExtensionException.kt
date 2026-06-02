package agents_engine.content

import java.nio.file.Path

/**
 * Thrown by [Files.load] when the path's extension doesn't map to any
 * known [Content] variant. Names the offending extension + path so
 * the error is debuggable.
 */
class UnknownExtensionException(val path: Path) : IllegalArgumentException(
    "Files.load: no Content variant for path \"$path\" (extension = " +
        "\"${path.fileName?.toString()?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() } ?: "<none>"}\"). " +
        "Construct the Content variant explicitly when the extension is ambiguous or missing, " +
        "or use Files.loadOrNull to skip silently. Known extensions: ${Files.knownExtensions.sorted()}.",
)
