package agents_engine.manifest.gradle

import javax.inject.Inject
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

open class AgentsKtManifestExtension @Inject constructor(
    objects: ObjectFactory,
    layout: ProjectLayout,
) {
    val entrypointClass: Property<String> = objects.property(String::class.java)
    val outputJson: RegularFileProperty = objects.fileProperty()
        .convention(layout.buildDirectory.file("agents/permissions.json"))
    val outputYaml: RegularFileProperty = objects.fileProperty()
        .convention(layout.buildDirectory.file("agents/permissions.yaml"))
    val baselineJson: RegularFileProperty = objects.fileProperty()
        .convention(layout.projectDirectory.file("agents/permissions.baseline.json"))
    val failOnFindings: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
}
