package agents_engine.composition

import agents_engine.core.*
import agents_engine.composition.forum.times
import agents_engine.composition.parallel.div
import agents_engine.composition.pipeline.then
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AgentPlacementTest {

    data class A(val v: String)
    data class B(val v: String)
    data class C(val v: String)
    data class D(val v: String)

    private inline fun <reified IN : Any, reified OUT : Any> stubAgent(name: String, sampleOut: OUT): Agent<IN, OUT> =
        agent(name) {
            skills {
                skill<IN, OUT>("$name-skill") {
                    implementedBy { sampleOut }
                }
            }
        }

    // ─── Pipeline ───

    @Test
    fun agentCanBePlacedInPipelineOnce() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<B, C>("b", C("c"))
        val pipeline = a then b
        assert(pipeline.agents.size == 2)
    }

    @Test
    fun agentCannotBePlacedInTwoPipelines() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<B, C>("b", C("c"))
        val c = stubAgent<B, C>("c", C("c"))

        a then b

        assertThrows<IllegalArgumentException> {
            a then c
        }
    }

    @Test
    fun agentCannotAppearTwiceInSamePipeline() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<B, A>("b", A("a"))

        val pipeline = a then b

        assertThrows<IllegalArgumentException> {
            pipeline then a
        }
    }

    // ─── Forum ───

    @Test
    fun agentCanBePlacedInForumOnce() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<A, C>("b", C("c"))
        val forum = a * b
        assert(forum.agents.size == 2)
    }

    @Test
    fun agentCannotBePlacedInTwoForums() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<A, C>("b", C("c"))
        val c = stubAgent<A, C>("c", C("c"))

        a * b

        assertThrows<IllegalArgumentException> {
            a * c
        }
    }

    @Test
    fun agentCannotAppearTwiceInSameForum() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<A, C>("b", C("c"))

        val forum = a * b

        assertThrows<IllegalArgumentException> {
            forum * a
        }
    }

    // ─── Cross: Pipeline + Forum ───

    @Test
    fun agentInPipelineCannotBeReusedInForum() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<B, C>("b", C("c"))
        val c = stubAgent<A, C>("c", C("c"))

        a then b

        assertThrows<IllegalArgumentException> {
            a * c
        }
    }

    @Test
    fun agentInForumCannotBeReusedInPipeline() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<A, C>("b", C("c"))
        val c = stubAgent<B, C>("c", C("c"))

        a * b

        assertThrows<IllegalArgumentException> {
            a then c
        }
    }

    // ─── Parallel ───

    @Test
    fun agentCanBePlacedInParallelOnce() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<A, B>("b", B("b"))
        val parallel = a / b
        assert(parallel.agents.size == 2)
    }

    @Test
    fun agentCannotBePlacedInTwoParallels() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<A, B>("b", B("b"))
        val c = stubAgent<A, B>("c", B("b"))

        a / b

        assertThrows<IllegalArgumentException> {
            a / c
        }
    }

    @Test
    fun agentCannotAppearTwiceInSameParallel() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<A, B>("b", B("b"))

        val parallel = a / b

        assertThrows<IllegalArgumentException> {
            parallel / a
        }
    }

    // ─── Cross: Parallel + Pipeline ───

    @Test
    fun agentInParallelCannotBeReusedInPipeline() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<A, B>("b", B("b"))
        val c = stubAgent<B, C>("c", C("c"))

        a / b

        assertThrows<IllegalArgumentException> {
            a then c
        }
    }

    @Test
    fun agentInPipelineCannotBeReusedInParallel() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<B, C>("b", C("c"))
        val c = stubAgent<A, B>("c", B("b"))

        a then b

        assertThrows<IllegalArgumentException> {
            a / c
        }
    }

    // ─── Cross: Parallel + Forum ───

    @Test
    fun agentInParallelCannotBeReusedInForum() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<A, B>("b", B("b"))
        val c = stubAgent<A, C>("c", C("c"))

        a / b

        assertThrows<IllegalArgumentException> {
            a * c
        }
    }

    @Test
    fun agentInForumCannotBeReusedInParallel() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<A, C>("b", C("c"))
        val c = stubAgent<A, B>("c", B("b"))

        a * b

        assertThrows<IllegalArgumentException> {
            a / c
        }
    }

    // ─── Connector agents (boundary between structure types) ───

    @Test
    fun agentLeadingIntoForumIsTracked() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<B, C>("b", C("c"))
        val c = stubAgent<B, D>("c", D("d"))
        val d = stubAgent<A, B>("d", B("b"))

        a then (b * c)  // a is a connector: leads pipeline into forum

        assertThrows<IllegalArgumentException> {
            a / d  // a was already placed — must throw
        }
    }

    @Test
    fun agentLeadingIntoParallelIsTracked() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<B, C>("b", C("c"))
        val c = stubAgent<B, C>("c", C("c"))
        val d = stubAgent<A, B>("d", B("b"))

        a then (b / c)  // a is a connector: leads pipeline into parallel

        assertThrows<IllegalArgumentException> {
            a / d  // a was already placed — must throw
        }
    }

    @Test
    fun aggregatorAfterParallelIsTracked() {
        val a = stubAgent<A, B>("a", B("b"))
        val b = stubAgent<A, B>("b", B("b"))
        val agg = stubAgent<List<B>, C>("agg", C("c"))

        (a / b) then agg  // agg is a connector: trails parallel into pipeline

        val c = stubAgent<A, B>("c", B("b"))
        val d = stubAgent<A, B>("d", B("b"))
        assertThrows<IllegalArgumentException> {
            (c / d) then agg  // agg was already placed — must throw
        }
    }
}
