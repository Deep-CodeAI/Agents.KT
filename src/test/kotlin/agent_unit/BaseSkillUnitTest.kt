package agent_unit

import agents_engine.core.Skill
import agents_engine.core.agent
import agents_engine.core.skill
import org.junit.jupiter.api.Assertions
import java.util.UUID
import kotlin.test.Test

class BaseSkillUnitTest {

    @Test
    fun baseSkillsAreCallable() {
        data class TaskRequest(val content: String)
        data class Result(val printedContent: String)

        val printContent = skill<TaskRequest, String>("printer") {
            implementedBy { input -> "Printed '${input.content}'" }
        }

        val agent = agent<TaskRequest, Result>("HelloWorldAgentPrinter") {
            skills {
                +printContent
                skill<String, Result>("answerer") {}
            }
        }

        val input = TaskRequest("Some mock request ${UUID.randomUUID()}")
        val output = (agent.skills["printer"] as Skill<TaskRequest, String>).execute(input)
        Assertions.assertEquals("Printed '${input.content}'", output)
    }
}
