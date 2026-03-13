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

    // ─── Pipeline ───

    @Test
    fun agentCanBePlacedInPipelineOnce() {
        val a = agent<A, B>("a") {}
        val b = agent<B, C>("b") {}
        val pipeline = a then b
        assert(pipeline.agents.size == 2)
    }

    @Test
    fun agentCannotBePlacedInTwoPipelines() {
        val a = agent<A, B>("a") {}
        val b = agent<B, C>("b") {}
        val c = agent<B, C>("c") {}

        a then b

        assertThrows<IllegalArgumentException> {
            a then c
        }
    }

    @Test
    fun agentCannotAppearTwiceInSamePipeline() {
        val a = agent<A, B>("a") {}
        val b = agent<B, A>("b") {}

        val pipeline = a then b

        assertThrows<IllegalArgumentException> {
            pipeline then a
        }
    }

    // ─── Forum ───

    @Test
    fun agentCanBePlacedInForumOnce() {
        val a = agent<A, B>("a") {}
        val b = agent<A, C>("b") {}
        val forum = a * b
        assert(forum.agents.size == 2)
    }

    @Test
    fun agentCannotBePlacedInTwoForums() {
        val a = agent<A, B>("a") {}
        val b = agent<A, C>("b") {}
        val c = agent<A, C>("c") {}

        a * b

        assertThrows<IllegalArgumentException> {
            a * c
        }
    }

    @Test
    fun agentCannotAppearTwiceInSameForum() {
        val a = agent<A, B>("a") {}
        val b = agent<A, C>("b") {}

        val forum = a * b

        assertThrows<IllegalArgumentException> {
            forum * a
        }
    }

    // ─── Cross: Pipeline + Forum ───

    @Test
    fun agentInPipelineCannotBeReusedInForum() {
        val a = agent<A, B>("a") {}
        val b = agent<B, C>("b") {}
        val c = agent<A, C>("c") {}

        a then b

        assertThrows<IllegalArgumentException> {
            a * c
        }
    }

    @Test
    fun agentInForumCannotBeReusedInPipeline() {
        val a = agent<A, B>("a") {}
        val b = agent<A, C>("b") {}
        val c = agent<B, C>("c") {}

        a * b

        assertThrows<IllegalArgumentException> {
            a then c
        }
    }

    // ─── Parallel ───

    @Test
    fun agentCanBePlacedInParallelOnce() {
        val a = agent<A, B>("a") {}
        val b = agent<A, B>("b") {}
        val parallel = a / b
        assert(parallel.agents.size == 2)
    }

    @Test
    fun agentCannotBePlacedInTwoParallels() {
        val a = agent<A, B>("a") {}
        val b = agent<A, B>("b") {}
        val c = agent<A, B>("c") {}

        a / b

        assertThrows<IllegalArgumentException> {
            a / c
        }
    }

    @Test
    fun agentCannotAppearTwiceInSameParallel() {
        val a = agent<A, B>("a") {}
        val b = agent<A, B>("b") {}

        val parallel = a / b

        assertThrows<IllegalArgumentException> {
            parallel / a
        }
    }

    // ─── Cross: Parallel + Pipeline ───

    @Test
    fun agentInParallelCannotBeReusedInPipeline() {
        val a = agent<A, B>("a") {}
        val b = agent<A, B>("b") {}
        val c = agent<B, C>("c") {}

        a / b

        assertThrows<IllegalArgumentException> {
            a then c
        }
    }

    @Test
    fun agentInPipelineCannotBeReusedInParallel() {
        val a = agent<A, B>("a") {}
        val b = agent<B, C>("b") {}
        val c = agent<A, B>("c") {}

        a then b

        assertThrows<IllegalArgumentException> {
            a / c
        }
    }

    // ─── Cross: Parallel + Forum ───

    @Test
    fun agentInParallelCannotBeReusedInForum() {
        val a = agent<A, B>("a") {}
        val b = agent<A, B>("b") {}
        val c = agent<A, C>("c") {}

        a / b

        assertThrows<IllegalArgumentException> {
            a * c
        }
    }

    @Test
    fun agentInForumCannotBeReusedInParallel() {
        val a = agent<A, B>("a") {}
        val b = agent<A, C>("b") {}
        val c = agent<A, B>("c") {}

        a * b

        assertThrows<IllegalArgumentException> {
            a / c
        }
    }

    // ─── Connector agents (boundary between structure types) ───

    @Test
    fun agentLeadingIntoForumIsTracked() {
        val a = agent<A, B>("a") {}
        val b = agent<B, C>("b") {}
        val c = agent<B, D>("c") {}
        val d = agent<A, B>("d") {}

        a then (b * c)  // a is a connector: leads pipeline into forum

        assertThrows<IllegalArgumentException> {
            a / d  // a was already placed — must throw
        }
    }

    @Test
    fun agentLeadingIntoParallelIsTracked() {
        val a = agent<A, B>("a") {}
        val b = agent<B, C>("b") {}
        val c = agent<B, C>("c") {}
        val d = agent<A, B>("d") {}

        a then (b / c)  // a is a connector: leads pipeline into parallel

        assertThrows<IllegalArgumentException> {
            a / d  // a was already placed — must throw
        }
    }

    @Test
    fun aggregatorAfterParallelIsTracked() {
        val a = agent<A, B>("a") {}
        val b = agent<A, B>("b") {}
        val agg = agent<List<B>, C>("agg") {}

        (a / b) then agg  // agg is a connector: trails parallel into pipeline

        val c = agent<A, B>("c") {}
        val d = agent<A, B>("d") {}
        assertThrows<IllegalArgumentException> {
            (c / d) then agg  // agg was already placed — must throw
        }
    }
}
