package agents_engine.core

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
}
