package agents_engine.composition.forum

import agents_engine.core.*
import agents_engine.composition.pipeline.then
import org.junit.jupiter.api.Test

class AgentsForumTest {

    private inline fun <reified IN : Any, reified OUT : Any> stubAgent(name: String, sampleOut: OUT): Agent<IN, OUT> =
        agent(name) {
            skills {
                skill<IN, OUT>("$name-skill") {
                    implementedBy { sampleOut }
                }
            }
        }

    @Test
    fun test() {
        data class Input(val text: String)
        data class Specs(val text: String)
        data class Result(val text: String)
        data class Opinion(val text: String)
        data class Opinions(val opinions: List<Opinion>)
        val inputToSpecsConverter = stubAgent<Input, Specs>("inputToSpecs", Specs("specs"))

        val forumInitiationAgent = stubAgent<Specs, Opinion>("forumStarter", Opinion("start"))
        val crazyCodeSlopGenerator = stubAgent<Specs, Opinion>("crazyCodeSlopGenerator", Opinion("slop"))
        val passiveCodeGenerator = stubAgent<Specs, Opinions>("passiveCodeGenerator", Opinions(emptyList()))
        val answerMaster = stubAgent<Specs, Result>("answerMaster", Result("result"))
        val printMaster = stubAgent<Result, String>("messenger", "sent")

        val pipeline = inputToSpecsConverter then (forumInitiationAgent * crazyCodeSlopGenerator * passiveCodeGenerator * answerMaster) then printMaster
    }
}
