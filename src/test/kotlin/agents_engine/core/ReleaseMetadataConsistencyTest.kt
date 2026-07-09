package agents_engine.core

import agents_engine.model.ModelProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Single-source-of-truth guard (#0.8.2 release-truth). An external audit found the release version + provider
// claims drifting across README / roadmap / comparison / site (0.8.1 shipped but several surfaces still said
// 0.8.0/0.7.2). `release-metadata.yaml` is now the one place those claims live; this test pins the in-repo
// surfaces to it, so the next release that edits only the metadata file fails the build until the prose moves.
class ReleaseMetadataConsistencyTest {

    private fun read(relative: String): String {
        val path = Path.of(relative)
        assertTrue(Files.exists(path), "expected $relative to exist (tests run from the repo root)")
        return Files.readString(path)
    }

    private val metadata = read("release-metadata.yaml")

    // Top-level scalar: the line `key: value [# comment]` at column 0; strip inline comment + quotes.
    private fun scalar(key: String): String {
        val line = metadata.lineSequence().firstOrNull { it.startsWith("$key:") }
            ?: error("release-metadata.yaml is missing a top-level scalar '$key'")
        return line.substringAfter(':').substringBefore('#').trim().trim('"')
    }

    private val currentRelease = scalar("currentRelease")
    private val developmentVersion = scalar("developmentVersion")
    private val providers = scalar("providers").toInt()

    @Test
    fun `provider count in metadata tracks ModelProvider entries`() {
        assertEquals(
            ModelProvider.entries.size, providers,
            "release-metadata.yaml providers=$providers but ModelProvider has ${ModelProvider.entries.size} entries",
        )
    }

    @Test
    fun `gradle development version matches metadata`() {
        val gradleVersion = Regex("""^version\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(read("build.gradle.kts"))?.groupValues?.get(1) ?: error("no version in build.gradle.kts")
        // On normal dev `main` the build is the -SNAPSHOT dev version; during a release commit it is exactly the
        // currentRelease (checkReadmeVersion / checkSnapshotPolicy own that transition). Accept either.
        assertTrue(
            gradleVersion == developmentVersion || gradleVersion == currentRelease,
            "build.gradle.kts version '$gradleVersion' is neither the metadata developmentVersion " +
                "'$developmentVersion' nor currentRelease '$currentRelease'",
        )
    }

    @Test
    fun `README dependency snippet and Current Release both name the current release`() {
        val readme = read("README.md")
        assertTrue(
            readme.contains("ai.deep-code:agents-kt:$currentRelease"),
            "README dependency snippet must advertise the current release $currentRelease",
        )
        val currentReleaseSection = readme.substringAfter("## Current Release").substringBefore("\n## ")
        assertTrue(
            currentReleaseSection.contains(currentRelease),
            "README 'Current Release' section must name $currentRelease (found stale content)",
        )
    }

    @Test
    fun `roadmap and comparison name the current release`() {
        assertTrue(
            read("docs/roadmap.md").contains(currentRelease),
            "docs/roadmap.md must mention the current release $currentRelease",
        )
        val comparison = read("docs/comparison.md")
        assertTrue(
            comparison.contains("$currentRelease (latest release)"),
            "docs/comparison.md must mark $currentRelease as the latest release",
        )
    }
}
