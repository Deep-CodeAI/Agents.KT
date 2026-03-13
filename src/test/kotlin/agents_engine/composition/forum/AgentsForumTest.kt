package agents_engine.composition.forum

import agents_engine.core.*
import agents_engine.composition.pipeline.then
import org.junit.jupiter.api.Test

class AgentsForumTest {

    @Test
    fun test() {
        data class Input(val text: String)
        data class Specs(val text: String)
        data class Task(val specs: Specs, val text: String)
        data class Result(val text: String)
        data class Opinion(val text: String)
        data class Opinions(val opinions: List<Opinion>)
        val inputToSpecsConverter = agent<Input, Specs>("inputToSpecs") {}

        val forumInitiationAgent = agent<Specs, Task>("forumStarter") {}
        val crazyCodeSlopGenerator = agent<Task, Opinion>("crazyCodeSlopGenerator") {}
        val passiveCodeGenerator = agent<Task, Opinion>("passiveCodeGenerator") {}
        val opinionsArbitrageMaster = agent<Task, Opinions>("passiveCodeGenerator") {}
        val answerMaster = agent<Opinions, Result>("passiveCodeGenerator") {}
        val printMaster = agent<Result, String>("messenger") {}

        val pipeline = inputToSpecsConverter then (forumInitiationAgent * opinionsArbitrageMaster * crazyCodeSlopGenerator * passiveCodeGenerator * answerMaster) then printMaster
    }
}
