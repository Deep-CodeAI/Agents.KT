package agents_engine.composition.parallel

import agents_engine.core.*
import agents_engine.composition.pipeline.Pipeline
import agents_engine.composition.pipeline.then
import org.junit.jupiter.api.Test

class AgentsParallelTest {

    data class Input(val v: String)
    data class Output(val v: String)
    data class Review(val v: String)
    data class Final(val v: String)
    data class Spec(val v: String)

    private inline fun <reified IN : Any, reified OUT : Any> stubAgent(name: String, sampleOut: OUT): Agent<IN, OUT> =
        agent(name) {
            skills {
                skill<IN, OUT>("$name-skill") {
                    implementedBy { sampleOut }
                }
            }
        }

    // ─── Basic structure ───

    @Test
    fun parallelCanBeCreated() {
        val a = stubAgent<Input, Output>("a", Output("a"))
        val b = stubAgent<Input, Output>("b", Output("b"))
        val parallel = a / b
        assert(parallel.agents.size == 2)
    }

    @Test
    fun parallelCanGrowWithMoreAgents() {
        val a = stubAgent<Input, Output>("a", Output("a"))
        val b = stubAgent<Input, Output>("b", Output("b"))
        val c = stubAgent<Input, Output>("c", Output("c"))
        val parallel = a / b / c
        assert(parallel.agents.size == 3)
    }

    // ─── Composition with Pipeline ───

    @Test
    fun agentThenParallelProducesListOut() {
        val first = stubAgent<Input, Spec>("first", Spec("spec"))
        val a = stubAgent<Spec, Review>("a", Review("a"))
        val b = stubAgent<Spec, Review>("b", Review("b"))
        val aggregator = stubAgent<List<Review>, Final>("aggregator", Final("done"))

        val pipeline: Pipeline<Input, Final> = first then (a / b) then aggregator
        assert(pipeline.agents.size == 4)
    }

    @Test
    fun pipelineThenParallelThenAgent() {
        val first = stubAgent<Input, Spec>("first", Spec("spec"))
        val second = stubAgent<Spec, Spec>("second", Spec("spec-2"))
        val a = stubAgent<Spec, Review>("a", Review("a"))
        val b = stubAgent<Spec, Review>("b", Review("b"))
        val c = stubAgent<Spec, Review>("c", Review("c"))
        val aggregator = stubAgent<List<Review>, Final>("aggregator", Final("done"))

        val pipeline: Pipeline<Input, Final> = (first then second) then (a / b / c) then aggregator
        assert(pipeline.agents.size == 6)
    }

    @Test
    fun parallelThenAgentProducesPipeline() {
        val a = stubAgent<Input, Review>("a", Review("a"))
        val b = stubAgent<Input, Review>("b", Review("b"))
        val aggregator = stubAgent<List<Review>, Final>("aggregator", Final("done"))

        val pipeline: Pipeline<Input, Final> = (a / b) then aggregator
        assert(pipeline.agents.size == 3)
    }

    @Test
    fun parallelThenPipeline() {
        val a = stubAgent<Input, Review>("a", Review("a"))
        val b = stubAgent<Input, Review>("b", Review("b"))
        val first = stubAgent<List<Review>, Spec>("first", Spec("spec"))
        val second = stubAgent<Spec, Final>("second", Final("done"))

        val pipeline: Pipeline<Input, Final> = (a / b) then (first then second)
        assert(pipeline.agents.size == 4)
    }
}
