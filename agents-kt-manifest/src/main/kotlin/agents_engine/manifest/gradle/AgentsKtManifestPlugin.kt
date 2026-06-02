package agents_engine.manifest.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

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

// The reflective entrypoint→manifest loader now lives in Gradle-free
// agents_engine.manifest.ManifestEntrypointLoader, shared with the native CLI (#1923).
