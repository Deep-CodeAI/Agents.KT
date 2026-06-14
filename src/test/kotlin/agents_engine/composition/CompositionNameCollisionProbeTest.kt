package agents_engine.composition

import agents_engine.composition.forum.times
import agents_engine.composition.parallel.div
import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PROBE — concurrent composition demuxes streamed events by `agentId`, which is the agent's
 * name. If two participants share a name, their interleaved events are indistinguishable: a
 * consumer cannot tell which leg produced which token. The single-placement rule catches an
 * agent placed twice, but NOT two distinct same-named instances. This must fail loud at
 * construction — the same stance the framework takes for duplicate tool/skill names.
 */
class CompositionNameCollisionProbeTest {

    private fun worker(name: String) = agent<String, String>(name) {
        skills { skill<String, String>("run", "runs") { implementedBy { "$name:$it" } } }
    }

    @Test
    fun `parallel rejects two agents sharing a name`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            worker("dup") / worker("dup")
        }
        assertTrue("dup" in ex.message.orEmpty(), "message must name the collision: ${ex.message}")
    }

    @Test
    fun `chained parallel rejects a duplicate added later`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            worker("a") / worker("b") / worker("a")
        }
        assertTrue("a" in ex.message.orEmpty(), "message must name the collision: ${ex.message}")
    }

    @Test
    fun `forum rejects two participants sharing a name`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            worker("dup") * worker("dup")
        }
        assertTrue("dup" in ex.message.orEmpty(), "message must name the collision: ${ex.message}")
    }

    @Test
    fun `distinct names are accepted`() {
        // Sanity: the guard must not reject legitimate distinct-name compositions.
        worker("a") / worker("b") / worker("c")
        worker("x") * worker("y")
    }
}
