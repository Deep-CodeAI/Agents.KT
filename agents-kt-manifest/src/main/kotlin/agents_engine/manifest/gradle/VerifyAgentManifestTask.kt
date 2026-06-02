package agents_engine.manifest.gradle

import agents_engine.manifest.ManifestEntrypointLoader
import agents_engine.manifest.PermissionManifest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

abstract class VerifyAgentManifestTask : DefaultTask() {
    @get:Input
    abstract val entrypointClass: Property<String>

    @get:Classpath
    val runtimeClasspath: ConfigurableFileCollection = project.objects.fileCollection()

    @get:InputFile
    @get:Optional
    abstract val baselineJson: RegularFileProperty

    @get:Input
    abstract val failOnFindings: Property<Boolean>

    @TaskAction
    fun verify() {
        val baselineFile = baselineJson.get().asFile
        if (!baselineFile.isFile) {
            throw GradleException(
                "Permission manifest baseline not found at ${baselineFile.absolutePath}. " +
                    "Run agentManifest, review the output, and check in an approved baseline.",
            )
        }

        val current = ManifestEntrypointLoader.load(entrypointClass.get(), runtimeClasspath.files)
        val baseline = PermissionManifest.fromJson(baselineFile.readText())
        val result = current.verifyAgainst(baseline)
        if (!result.ok && failOnFindings.get()) {
            val details = result.findings.joinToString("\n") { finding ->
                "- [${finding.severity}] ${finding.code} ${finding.path}: ${finding.message}"
            }
            throw GradleException("Permission manifest verification failed:\n$details")
        }
    }
}
