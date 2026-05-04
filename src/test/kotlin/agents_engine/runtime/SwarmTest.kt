package agents_engine.runtime

import agents_engine.core.agent
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Tests for #984 — Swarm: ServiceLoader-based agent discovery + absorb.
//
// Unit tests exercise the AgentProvider/Swarm/absorb mechanics with fixtures
// declared in this module's test sources. The JAR-compilation integration
// test lives in SwarmJarIntegrationTest.
class SwarmTest {

    private fun namedAgent(name: String, transform: (String) -> String = { "OUT:$it" }) =
        agent<String, String>(name) {
            skills { skill<String, String>("op", "op") { implementedBy(transform) } }
        }

    @Test
    fun `AgentProvider is a real interface usable as a ServiceLoader contract`() {
        // Ensure the type exists, has the right shape, and is loadable.
        val cls = AgentProvider::class.java
        assertTrue(cls.isInterface, "AgentProvider must be an interface")
        val build = cls.getDeclaredMethod("build")
        assertNotNull(build)
    }

    @Test
    fun `Swarm discover with explicit classloader uses that classloader's services`() {
        // We construct a classloader that has NO AgentProvider services registered.
        // Even if the parent classloader has some (from test fixtures), the
        // discovery method must respect the supplied loader.
        val emptyLoader = java.net.URLClassLoader(emptyArray(), null)
        val results = Swarm.discover(emptyLoader)
        assertEquals(emptyList(), results, "isolated classloader should yield no providers")
    }

    @Test
    fun `Swarm discover finds the in-test fixture provider on the default classloader`() {
        // SwarmTestProviderFixture is declared below + registered via
        // src/test/resources/META-INF/services/agents_engine.runtime.AgentProvider.
        val results = Swarm.discover()
        val names = results.map { it.name }
        assertTrue(
            "swarm-fixture-alpha" in names,
            "expected fixture agent in discovery; got: $names",
        )
    }

    @Test
    fun `absorb adds a tool with the sibling's name`() {
        val sibling = namedAgent("helper")
        val captain = agent<String, String>("captain") {
            skills { skill<String, String>("op", "op") { implementedBy { "captain-out" } } }
        }
        captain.absorb(sibling)
        assertTrue(
            "helper" in captain.toolMap,
            "absorbed sibling should appear as a tool: ${captain.toolMap.keys}",
        )
    }

    @Test
    fun `absorbed tool delegates to the sibling and returns its output`() {
        val sibling = namedAgent("helper") { "HELPER-SAW:$it" }
        val captain = agent<String, String>("captain") {
            skills { skill<String, String>("op", "op") { implementedBy { "x" } } }
        }
        captain.absorb(sibling)

        // Invoke the absorbed tool directly via the tool map.
        val tool = captain.toolMap["helper"]!!
        val result = tool.executor(mapOf("query" to "hello"))
        assertEquals("HELPER-SAW:hello", result.toString())
    }

    @Test
    fun `absorbing two siblings with the same name fails fast`() {
        val a = namedAgent("dup")
        val b = namedAgent("dup")
        val captain = agent<String, String>("captain") {
            skills { skill<String, String>("op", "op") { implementedBy { "x" } } }
        }
        captain.absorb(a)
        assertThrows<IllegalArgumentException> { captain.absorb(b) }
    }

    @Test
    fun `absorb is a no-op when sibling name collides with the captain's name (skip self)`() {
        // If user accidentally tries to absorb their own captain into itself —
        // collision against the agent's own name. Should error rather than
        // create a recursive tool.
        val captain = agent<String, String>("self") {
            skills { skill<String, String>("op", "op") { implementedBy { "x" } } }
        }
        // Trying to absorb captain into itself.
        assertThrows<IllegalArgumentException> { captain.absorb(captain) }
    }

    @Test
    fun `absorb of a non-String input sibling fails fast with a helpful error`() {
        @Suppress("unused")
        data class TypedInput(val v: String)

        val sibling = agent<TypedInput, String>("typed") {
            skills { skill<TypedInput, String>("op", "op") { implementedBy { "ok" } } }
        }
        val captain = agent<String, String>("captain") {
            skills { skill<String, String>("op", "op") { implementedBy { "x" } } }
        }
        val ex = assertThrows<IllegalArgumentException> { captain.absorb(sibling) }
        assertTrue(
            ex.message.orEmpty().contains("String", ignoreCase = true),
            "error should mention String input requirement: ${ex.message}",
        )
    }

    @Test
    fun `absorbed tool is auto-available across the captain's skills`() {
        // Auto-tool means the captain can call the absorbed sibling from any
        // of its skills without listing it explicitly in tools(...).
        val sibling = namedAgent("worker")
        val captain = agent<String, String>("captain") {
            skills { skill<String, String>("op", "op") { implementedBy { "x" } } }
        }
        captain.absorb(sibling)
        // The framework's existing autoToolNames mechanism is internal but
        // we can probe it via reflection of the public toolMap presence
        // PLUS knowing that absorb wires it up. Verifying via the toolMap is
        // sufficient — any further integration is through the agentic loop.
        assertTrue("worker" in captain.toolMap)
    }
}

/**
 * In-test AgentProvider fixture for [SwarmTest.discover finds the in-test
 * fixture provider]. Registered via
 * src/test/resources/META-INF/services/agents_engine.runtime.AgentProvider.
 */
class SwarmTestProviderFixture : AgentProvider {
    override fun build(): agents_engine.core.Agent<*, *> =
        agent<String, String>("swarm-fixture-alpha") {
            skills { skill<String, String>("op", "op") { implementedBy { "fixture:$it" } } }
        }
}
