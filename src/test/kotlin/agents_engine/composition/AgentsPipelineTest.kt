package agents_engine.composition

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

    @Test
    fun pipelineCanBeCreatedV2() {
        val first = agent<SomeAgentAsk, SomeIntermediate>("first") {}
        val second = agent<SomeIntermediate, SomeAgentResult>("second") {}
        val pipeline: Pipeline<SomeAgentAsk, SomeAgentResult> = first then second
    }

    @Test
    fun pipelineCanBeCreated() {
        val specMaster = agent<SomeSpecAsk, SomeSpec>("specMaster") {}
        val coderMaster = agent<SomeSpec, SomeCode>("coderMaster") {}
        val reviewMaster = agent<SomeCode, SomeReview>("reviewMaster") {}
        val productionMaster = agent<SomeReview, SomeProduction>("reviewMaster") {}

        val pipeline: Pipeline<SomeSpecAsk, SomeProduction> =
            specMaster then
            coderMaster then
            reviewMaster then
            productionMaster
    }

    @Test
    fun pipelineThenPipeline() {
        val specMaster = agent<SomeSpecAsk, SomeSpec>("specMaster") {}
        val coderMaster = agent<SomeSpec, SomeCode>("coderMaster") {}
        val reviewMaster = agent<SomeCode, SomeReview>("reviewMaster") {}
        val productionMaster = agent<SomeReview, SomeProduction>("reviewMaster") {}

        val pipelinePt1: Pipeline<SomeSpecAsk, SomeProduction> =
            specMaster then
                    coderMaster then
                    reviewMaster then
                    productionMaster

        val productionManager = agent<SomeProduction, SomeProductionManagement>("productionManager") {}
        val machineManager = agent<SomeProductionManagement, SomeProductionMachineryManagement>("machineManager") {}
        val pipelinePt2: Pipeline<SomeProduction, SomeProductionMachineryManagement> =
            productionManager then
                    machineManager
        val totalPipeline = pipelinePt1 then pipelinePt2
        val totalPipelineWithType: Pipeline<SomeSpecAsk, SomeProductionMachineryManagement> = pipelinePt1 then pipelinePt2
    }
}