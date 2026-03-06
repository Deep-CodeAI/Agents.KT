package agents_engine.core

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
        val a = agent<A, A>("a") { execute { it } }
        val b = agent<A, B>("b") { execute { B(it.v) } }

        a.loop { null }

        assertThrows<IllegalArgumentException> {
            a then b
        }
    }

    @Test
    fun agentInLoopCannotBeReusedInAnotherLoop() {
        val a = agent<A, A>("a") { execute { it } }

        a.loop { null }

        assertThrows<IllegalArgumentException> {
            a.loop { null }
        }
    }

    @Test
    fun agentInLoopCannotBeReusedInForum() {
        val a = agent<A, A>("a") { execute { it } }
        val b = agent<A, B>("b") { execute { B(it.v) } }

        a.loop { null }

        assertThrows<IllegalArgumentException> {
            a * b
        }
    }

    @Test
    fun agentInLoopCannotBeReusedInParallel() {
        val a = agent<A, A>("a") { execute { it } }
        val b = agent<A, A>("b") { execute { it } }

        a.loop { null }

        assertThrows<IllegalArgumentException> {
            a / b
        }
    }

    // ─── Cross: outer pipeline agents cannot enter loop ───

    @Test
    fun agentInPipelineCannotBeReusedInLoop() {
        val a = agent<A, B>("a") { execute { B(it.v) } }
        val b = agent<B, C>("b") { execute { C(it.v) } }

        a then b

        assertThrows<IllegalArgumentException> {
            a.loop { null }
        }
    }

    // ─── Loop at different positions in outer pipeline ───

    @Test
    fun loopInMiddleOfPipelinePassesOutputToNextStage() {
        val prepare = agent<String, Int>("prepare") { execute { it.length } }
        val refine  = agent<Int, Int>("refine")    { execute { it + 1 } }
        val wrap    = agent<Int, String>("wrap")    { execute { "[$it]" } }

        val loop = refine.loop { result -> if (result >= 5) null else result }

        val pipeline = prepare then loop then wrap

        // "ab".length=2, loop: 2→3→4→5 stop, "[5]"
        assertEquals("[5]", pipeline("ab"))
    }

    @Test
    fun loopAtEndOfPipeline() {
        val prepare = agent<Int, Int>("prepare") { execute { it * 3 } }
        val shrink  = agent<Int, Int>("shrink")  { execute { it / 2 } }

        val pipeline = prepare then shrink.loop { result -> if (result == 0) null else result }

        // prepare: 1*3=3, loop: 3→1→0 stop
        assertEquals(0, pipeline(1))
    }
}
