package agents_engine.core

import agents_engine.core.AgentEntityDSLTest.SomeAgentAsk
import agents_engine.core.AgentEntityDSLTest.SomeIntermediate
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AgentsSkillsTest {
    data class SomeSpecAsk(val v: String)
    data class SomeSpec(val v: String, val k: Long)

    @Test
    fun agentWithEmptySkillsFailsFastAtConstruction() {
        val error = assertThrows<IllegalArgumentException> {
            agent<SomeAgentAsk, SomeIntermediate>("testAgent") {
                skills {
                }
            }
        }

        kotlin.test.assertTrue(error.message!!.contains("must declare at least one skill"))
    }

    @Test
    fun agentWithSkillsCanBeCreated() {
        data class ReadFile(val path: String)
        data class ReadCode(val v: String)
        data class CheckedCode(val code: String, val errors: List<String>)
        data class ChangedCode(val newCode: String, val diffs: List<String>)
        data class WriteFile(val filePath: String)


        val agent = agent<ReadFile, WriteFile>("testAgentThatReadsFileAndWritesFile") {
            skills {
                skill<ReadFile, ReadCode>("readFileAndCode") {}
                skill<String, CheckedCode>("checkCode") {}
                skill<String, ChangedCode>("changeCode") {}
                skill<CheckedCode, WriteFile>("writeCodeToFile") {}
            }
        }
    }

    @Test
    fun agentWithSkillsMustHaveOneReturningAgentOut() {
        data class CodeBundle(val code: String)
        assertThrows<IllegalArgumentException> {
            agent<String, CodeBundle>("badAgentWithWrongSkills") {
                skills {
                    skill<String, String>("spell-check") {}
                }
            }
        }
    }

    @Test
    fun duplicateSkillNamesInSameBlockThrow() {
        assertThrows<IllegalArgumentException> {
            agent<String, String>("duplicateSkills") {
                skills {
                    skill<String, String>("dup") { implementedBy { it.uppercase() } }
                    skill<Int, String>("dup") { implementedBy { it.toString() } }
                }
            }
        }
    }

    @Test
    fun duplicateSkillNamesAcrossSkillsBlocksThrow() {
        assertThrows<IllegalArgumentException> {
            agent<String, String>("duplicateAcrossBlocks") {
                skills {
                    skill<String, String>("dup") { implementedBy { it } }
                }
                skills {
                    skill<String, String>("dup") { implementedBy { "$it!" } }
                }
            }
        }
    }

}
