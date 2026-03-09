package agents_engine.composition

import agents_engine.core.*
import org.junit.jupiter.api.Test

class AgentsParallelTest {

    data class Input(val v: String)
    data class Output(val v: String)
    data class Review(val v: String)
    data class Final(val v: String)
    data class Spec(val v: String)

    // ─── Basic structure ───

    @Test
    fun parallelCanBeCreated() {
        val a = agent<Input, Output>("a") {}
        val b = agent<Input, Output>("b") {}
        val parallel = a / b
        assert(parallel.agents.size == 2)
    }

    @Test
    fun parallelCanGrowWithMoreAgents() {
        val a = agent<Input, Output>("a") {}
        val b = agent<Input, Output>("b") {}
        val c = agent<Input, Output>("c") {}
        val parallel = a / b / c
        assert(parallel.agents.size == 3)
    }

    // ─── Composition with Pipeline ───

    @Test
    fun agentThenParallelProducesListOut() {
        val first = agent<Input, Spec>("first") {}
        val a = agent<Spec, Review>("a") {}
        val b = agent<Spec, Review>("b") {}
        val aggregator = agent<List<Review>, Final>("aggregator") {}

        val pipeline: Pipeline<Input, Final> = first then (a / b) then aggregator
        assert(pipeline.agents.size == 4)
    }

    @Test
    fun pipelineThenParallelThenAgent() {
        val first = agent<Input, Spec>("first") {}
        val second = agent<Spec, Spec>("second") {}
        val a = agent<Spec, Review>("a") {}
        val b = agent<Spec, Review>("b") {}
        val c = agent<Spec, Review>("c") {}
        val aggregator = agent<List<Review>, Final>("aggregator") {}

        val pipeline: Pipeline<Input, Final> = (first then second) then (a / b / c) then aggregator
        assert(pipeline.agents.size == 6)
    }

    @Test
    fun parallelThenAgentProducesPipeline() {
        val a = agent<Input, Review>("a") {}
        val b = agent<Input, Review>("b") {}
        val aggregator = agent<List<Review>, Final>("aggregator") {}

        val pipeline: Pipeline<Input, Final> = (a / b) then aggregator
        assert(pipeline.agents.size == 3)
    }

    @Test
    fun parallelThenPipeline() {
        val a = agent<Input, Review>("a") {}
        val b = agent<Input, Review>("b") {}
        val first = agent<List<Review>, Spec>("first") {}
        val second = agent<Spec, Final>("second") {}

        val pipeline: Pipeline<Input, Final> = (a / b) then (first then second)
        assert(pipeline.agents.size == 4)
    }
}
