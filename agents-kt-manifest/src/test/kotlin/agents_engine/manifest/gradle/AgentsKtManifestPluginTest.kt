package agents_engine.manifest.gradle

import kotlin.test.Test
import kotlin.test.assertNotNull
import org.gradle.testfixtures.ProjectBuilder

class AgentsKtManifestPluginTest {
    @Test
    fun `plugin registers manifest generation and verification tasks`() {
        val project = ProjectBuilder.builder().build()

        project.pluginManager.apply("ai.deep-code.agents-kt.manifest")

        assertNotNull(project.extensions.findByName("agentsKtManifest"))
        assertNotNull(project.tasks.findByName("agentManifest"))
        assertNotNull(project.tasks.findByName("verifyAgentManifest"))
    }
}
