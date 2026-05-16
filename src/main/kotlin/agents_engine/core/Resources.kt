package agents_engine.core

/**
 * `agents_engine/core/Resources.kt` — classpath resource loading helpers
 * ([loadResource], [loadResourceOrNull]). See
 * `src/main/resources/internals-agent/core/Resources.md` for the adjunct
 * surfaced to IDE-side LLM tools via `agents-kt-internals` (#1837 / #1841).
 */

/**
 * Read a UTF-8 classpath resource and return its full content. See #980.
 *
 * Canonical usage — pulling an agent's system prompt out of a .md file:
 *
 * ```kotlin
 * agent<IN, OUT>("coder") {
 *     prompt(loadResource("prompts/coder.md"))
 *     // ...
 * }
 * ```
 *
 * The lookup is fail-fast: a missing resource throws [IllegalArgumentException]
 * at the call site (which is normally inside `agent { }`), so a typo in the
 * path surfaces at agent construction rather than at first invocation.
 *
 * A leading slash on [path] is tolerated — `prompts/x.md` and `/prompts/x.md`
 * resolve to the same resource. Both forms work because of how class-loader
 * resource lookup differs from `Class#getResource`; normalizing here saves
 * the user from a footgun.
 *
 * @throws IllegalArgumentException when the resource is not on the classpath.
 *   Use [loadResourceOrNull] when an absent resource is expected.
 */
fun loadResource(path: String): String =
    loadResourceOrNull(path)
        ?: throw IllegalArgumentException(
            "Resource not found on classpath: \"$path\". " +
                "Place it under src/main/resources/ (or src/test/resources/ for tests) " +
                "and verify the path is relative to the resources root."
        )

/**
 * Like [loadResource] but returns `null` for a missing resource instead of
 * throwing. Use when the agent is designed to operate with or without a
 * particular resource present.
 */
fun loadResourceOrNull(path: String): String? {
    val normalized = path.trimStart('/')
    val cl = Thread.currentThread().contextClassLoader
        ?: ::loadResourceOrNull.javaClass.classLoader
    return cl.getResourceAsStream(normalized)
        ?.use { it.bufferedReader(Charsets.UTF_8).readText() }
}
