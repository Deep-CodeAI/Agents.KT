package agents_engine.composition

import agents_engine.core.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class LoopExecutionTest {

    @Test
    fun loopRunsOnceWhenBlockImmediatelyReturnsNull() {
        val count = mutableListOf<Int>()
        val loop = agent<Int, Int>("inc") {
            skills { skill<Int, Int>("inc") { implementedBy { input -> count.add(input); input + 1 } } }
        }.loop { null }

        assertEquals(1, loop(0))
        assertEquals(listOf(0), count)
    }

    @Test
    fun loopFeedsOutputBackAsInput() {
        val log = mutableListOf<Int>()
        val loop = agent<Int, Int>("inc") {
            skills { skill<Int, Int>("inc") { implementedBy { input -> log.add(input); input + 1 } } }
        }.loop { result -> if (result < 3) result else null }

        loop(0)
        assertEquals(listOf(0, 1, 2), log)
    }

    @Test
    fun loopRunsUntilConditionMet() {
        val loop = agent<Int, Int>("double") {
            skills { skill<Int, Int>("double") { implementedBy { it * 2 } } }
        }.loop { result -> if (result > 100) null else result }

        // 1 → 2 → 4 → 8 → 16 → 32 → 64 → 128 (stop)
        assertEquals(128, loop(1))
    }

    @Test
    fun loopOnPipelineWorks() {
        val pipeline = agent<Int, Int>("add1a") { skills { skill<Int, Int>("add1a") { implementedBy { it + 1 } } } } then
                       agent<Int, Int>("add1b") { skills { skill<Int, Int>("add1b") { implementedBy { it + 1 } } } }

        val loop = pipeline.loop { result -> if (result >= 10) null else result }

        // starts at 0, adds 2 per iteration: 0→2→4→6→8→10 (stop)
        assertEquals(10, loop(0))
    }

    @Test
    fun loopIsComposableInPipeline() {
        val prepare  = agent<String, Int>("len")  { skills { skill<String, Int>("len")  { implementedBy { it.length } } } }
        val process  = agent<Int, Int>("inc")     { skills { skill<Int, Int>("inc")     { implementedBy { it + 1 } } } }
        val finalize = agent<Int, String>("wrap") { skills { skill<Int, String>("wrap") { implementedBy { "result:$it" } } } }

        val loop = process.loop { result -> if (result >= 5) null else result }

        val pipeline = prepare then loop then finalize
        // "hi".length=2, loop: 2→3→4→5 stop, "result:5"
        assertEquals("result:5", pipeline("hi"))
    }
}
