package agents_engine.internal

/**
 * `agents_engine/internal/BuildInfo.kt` — single source of truth for the
 * runtime-visible Agents.KT version (#2806).
 *
 * Three separate version constants (`McpServer.SERVER_VERSION = "0.1.3"`,
 * `McpClient.CLIENT_VERSION = "0.1.3"`, `McpRunner.VERSION = "0.3.0"`)
 * had drifted against the actual project version and against each other.
 * They now all flow through [version], which reads
 * `Implementation-Version` from the published JAR's manifest (stamped by
 * `tasks.jar { manifest { attributes(...) } }` in `build.gradle.kts`).
 *
 * Fallback: when the class is loaded from a non-sealed classpath — IDE
 * runs against `build/classes`, gradle test runner, `./gradlew run` — the
 * manifest is absent and [version] is `"dev"`. That's the correct value
 * for those contexts (the artifact hasn't been packaged).
 *
 * Kept `internal` — callers go through the higher-level constants
 * exposed by `McpServer.SERVER_VERSION` / `McpClient.CLIENT_VERSION` /
 * `McpRunner.VERSION`, which all forward here.
 */
internal object BuildInfo {
    val version: String =
        BuildInfo::class.java.`package`?.implementationVersion ?: "dev"
}
