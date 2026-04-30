package agents_engine.composition.pipeline

import agents_engine.core.*
import agents_engine.core.AgentEntityDSLTest.SomeAgentAsk
import agents_engine.core.AgentEntityDSLTest.SomeAgentResult
import agents_engine.core.AgentEntityDSLTest.SomeIntermediate
import org.junit.jupiter.api.Test

class AgentsPipelineTest {
    data class SomeSpecAsk(val v: String)
    data class SomeSpec(val v: String, val k: Long)

    data class SomeCode(val v: String, val k: Long)
    data class SomeReview(val v: String, val k: Long)
    data class SomeProduction(val v: String, val k: Long)

    data class SomeProductionManagement(val v: String, val k: Long)
    data class SomeProductionMachineryManagement(val v: String, val k: Long)

    private inline fun <reified IN : Any, reified OUT : Any> stubAgent(name: String, sampleOut: OUT): Agent<IN, OUT> =
        agent(name) {
            skills {
                skill<IN, OUT>("$name-skill") {
                    implementedBy { sampleOut }
                }
            }
        }

    @Test
    fun pipelineCanBeCreatedV2() {
        val first = stubAgent<SomeAgentAsk, SomeIntermediate>("first", SomeIntermediate(1))
        val second = stubAgent<SomeIntermediate, SomeAgentResult>("second", SomeAgentResult("ok", 1))
        val pipeline: Pipeline<SomeAgentAsk, SomeAgentResult> = first then second
    }

    @Test
    fun pipelineCanBeCreated() {
        val specMaster = stubAgent<SomeSpecAsk, SomeSpec>("specMaster", SomeSpec("spec", 1))
        val coderMaster = stubAgent<SomeSpec, SomeCode>("coderMaster", SomeCode("code", 1))
        val reviewMaster = stubAgent<SomeCode, SomeReview>("reviewMaster", SomeReview("review", 1))
        val productionMaster = stubAgent<SomeReview, SomeProduction>("productionMaster", SomeProduction("prod", 1))

        val pipeline: Pipeline<SomeSpecAsk, SomeProduction> =
            specMaster then
            coderMaster then
            reviewMaster then
            productionMaster
    }

    @Test
    fun pipelineThenPipeline() {
        val specMaster = stubAgent<SomeSpecAsk, SomeSpec>("specMaster", SomeSpec("spec", 1))
        val coderMaster = stubAgent<SomeSpec, SomeCode>("coderMaster", SomeCode("code", 1))
        val reviewMaster = stubAgent<SomeCode, SomeReview>("reviewMaster", SomeReview("review", 1))
        val productionMaster = stubAgent<SomeReview, SomeProduction>("productionMaster", SomeProduction("prod", 1))

        val pipelinePt1: Pipeline<SomeSpecAsk, SomeProduction> =
            specMaster then
                    coderMaster then
                    reviewMaster then
                    productionMaster

        val productionManager = stubAgent<SomeProduction, SomeProductionManagement>(
            "productionManager",
            SomeProductionManagement("mgmt", 1),
        )
        val machineManager = stubAgent<SomeProductionManagement, SomeProductionMachineryManagement>(
            "machineManager",
            SomeProductionMachineryManagement("machinery", 1),
        )
        val pipelinePt2: Pipeline<SomeProduction, SomeProductionMachineryManagement> =
            productionManager then
                    machineManager
        val totalPipeline = pipelinePt1 then pipelinePt2
        val totalPipelineWithType: Pipeline<SomeSpecAsk, SomeProductionMachineryManagement> = pipelinePt1 then pipelinePt2
    }
}
