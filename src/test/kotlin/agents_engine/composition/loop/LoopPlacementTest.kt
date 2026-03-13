package agents_engine.composition.loop

import agents_engine.core.*
import agents_engine.composition.forum.times
import agents_engine.composition.parallel.div
import agents_engine.composition.pipeline.then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LoopPlacementTest {

    data class A(val v: Int)
    data class B(val v: Int)
    data class C(val v: Int)

    // ─── Agent inside loop is tracked ───

    @Test
    fun agentInLoopCannotBeReusedInPipeline() {
        val a = agent<A, A>("a") {}
        val b = agent<A, B>("b") {}

        a.loop { null }

        assertThrows<IllegalArgumentException> {
            a then b
        }
    }

    @Test
    fun agentInLoopCannotBeReusedInAnotherLoop() {
        val a = agent<A, A>("a") {}

        a.loop { null }

        assertThrows<IllegalArgumentException> {
            a.loop { null }
        }
    }

    @Test
    fun agentInLoopCannotBeReusedInForum() {
        val a = agent<A, A>("a") {}
        val b = agent<A, B>("b") {}

        a.loop { null }

        assertThrows<IllegalArgumentException> {
            a * b
        }
    }

    @Test
    fun agentInLoopCannotBeReusedInParallel() {
        val a = agent<A, A>("a") {}
        val b = agent<A, A>("b") {}

        a.loop { null }

        assertThrows<IllegalArgumentException> {
            a / b
        }
    }

    // ─── Cross: outer pipeline agents cannot enter loop ───

    @Test
    fun agentInPipelineCannotBeReusedInLoop() {
        val a = agent<A, B>("a") {}
        val b = agent<B, C>("b") {}

        a then b

        assertThrows<IllegalArgumentException> {
            a.loop { null }
        }
    }

    // ─── Loop at different positions in outer pipeline ───

    @Test
    fun loopInMiddleOfPipelinePassesOutputToNextStage() {
        val prepare = agent<String, Int>("prepare") { skills { skill<String, Int>("prepare") { implementedBy { it.length } } } }
        val refine  = agent<Int, Int>("refine")     { skills { skill<Int, Int>("refine")     { implementedBy { it + 1 } } } }
        val wrap    = agent<Int, String>("wrap")    { skills { skill<Int, String>("wrap")    { implementedBy { "[$it]" } } } }

        val loop = refine.loop { result -> if (result >= 5) null else result }

        val pipeline = prepare then loop then wrap

        // "ab".length=2, loop: 2→3→4→5 stop, "[5]"
        assertEquals("[5]", pipeline("ab"))
    }

    @Test
    fun loopAtEndOfPipeline() {
        val prepare = agent<Int, Int>("prepare") { skills { skill<Int, Int>("prepare") { implementedBy { it * 3 } } } }
        val shrink  = agent<Int, Int>("shrink")  { skills { skill<Int, Int>("shrink")  { implementedBy { it / 2 } } } }

        val pipeline = prepare then shrink.loop { result -> if (result == 0) null else result }

        // prepare: 1*3=3, loop: 3→1→0 stop
        assertEquals(0, pipeline(1))
    }
}
