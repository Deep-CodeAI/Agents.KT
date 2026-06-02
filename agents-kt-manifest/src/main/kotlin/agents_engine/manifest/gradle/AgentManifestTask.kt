package agents_engine.manifest.gradle

import agents_engine.manifest.ManifestEntrypointLoader
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class AgentManifestTask : DefaultTask() {
    @get:Input
    abstract val entrypointClass: Property<String>

    @get:Classpath
    val runtimeClasspath: ConfigurableFileCollection = project.objects.fileCollection()

    @get:OutputFile
    abstract val outputJson: RegularFileProperty

    @get:OutputFile
    abstract val outputYaml: RegularFileProperty

    @TaskAction
    fun generate() {
        val manifest = ManifestEntrypointLoader.load(entrypointClass.get(), runtimeClasspath.files)
        manifest.writeJson(outputJson.get().asFile)
        manifest.writeYaml(outputYaml.get().asFile)
    }
}
