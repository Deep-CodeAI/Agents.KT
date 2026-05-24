package agents_engine.manifest.gradle

import agents_engine.composition.branch.Branch
import agents_engine.composition.forum.Forum
import agents_engine.composition.loop.Loop
import agents_engine.composition.parallel.Parallel
import agents_engine.composition.pipeline.Pipeline
import agents_engine.core.Agent
import agents_engine.mcp.McpServer
import agents_engine.manifest.PermissionManifest
import agents_engine.manifest.PermissionManifestProvider
import agents_engine.manifest.permissionManifest
import java.net.URLClassLoader
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

class AgentsKtManifestPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "agentsKtManifest",
            AgentsKtManifestExtension::class.java,
            project.objects,
            project.layout,
        )

        project.tasks.register("agentManifest", AgentManifestTask::class.java) { task ->
            task.group = "verification"
            task.description = "Generates deterministic Agents.KT permission manifest JSON/YAML."
            task.entrypointClass.set(extension.entrypointClass)
            project.configurations.findByName("runtimeClasspath")?.let { task.runtimeClasspath.from(it) }
            task.outputJson.set(extension.outputJson)
            task.outputYaml.set(extension.outputYaml)
        }

        project.tasks.register("verifyAgentManifest", VerifyAgentManifestTask::class.java) { task ->
            task.group = "verification"
            task.description = "Fails when the current permission manifest widens high-risk boundaries."
            task.entrypointClass.set(extension.entrypointClass)
            project.configurations.findByName("runtimeClasspath")?.let { task.runtimeClasspath.from(it) }
            task.baselineJson.set(extension.baselineJson)
            task.failOnFindings.set(extension.failOnFindings)
        }
    }
}

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

private object ManifestEntrypointLoader {
    fun load(className: String, runtimeClasspath: Set<java.io.File>): PermissionManifest {
        val urls = runtimeClasspath.map { it.toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, javaClass.classLoader).use { loader ->
            val klass = Class.forName(className, true, loader)
            val kotlinObject = kotlinObjectInstance(klass)
            if (kotlinObject is PermissionManifestProvider) {
                return kotlinObject.permissionManifest()
            }

            val staticMethod = klass.methods.firstOrNull { method ->
                method.name == "permissionManifest" &&
                    method.parameterCount == 0 &&
                    java.lang.reflect.Modifier.isStatic(method.modifiers)
            }
            if (staticMethod != null) {
                return coerceManifest(staticMethod.invoke(null))
            }

            val instance = kotlinObject ?: noArgInstance(klass)
            val instanceMethod = klass.methods.firstOrNull { method ->
                method.name == "permissionManifest" && method.parameterCount == 0
            } ?: throw GradleException(
                "Manifest entrypoint $className must implement PermissionManifestProvider " +
                    "or expose a no-arg permissionManifest() method.",
            )
            return coerceManifest(instanceMethod.invoke(instance))
        }
    }

    private fun kotlinObjectInstance(klass: Class<*>): Any? =
        runCatching { klass.getField("INSTANCE").get(null) }.getOrNull()

    private fun noArgInstance(klass: Class<*>): Any =
        klass.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()

    @Suppress("UNCHECKED_CAST")
    private fun coerceManifest(value: Any?): PermissionManifest =
        when (value) {
            is PermissionManifest -> value
            is Agent<*, *> -> value.permissionManifest()
            is Pipeline<*, *> -> value.permissionManifest()
            is Parallel<*, *> -> value.permissionManifest()
            is Forum<*, *> -> value.permissionManifest()
            is Loop<*, *> -> value.permissionManifest()
            is Branch<*, *> -> value.permissionManifest()
            is McpServer -> value.permissionManifest()
            else -> throw GradleException(
                "permissionManifest() returned ${value?.let { it::class.qualifiedName } ?: "null"}; " +
                    "expected PermissionManifest, Agent, or an Agents.KT composition.",
            )
        }
}
