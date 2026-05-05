package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #1016 — `Skill.tools(...)` accepts typed `Tool<*, *>` handles in addition to
 * the legacy stringly-typed form. The two forms produce identical
 * `Skill.toolNames` and dispatch identically through the agentic loop.
 *
 * The string form stays — typed and string overloads coexist; #1017 will
 * deprecate the string form (warning level), but not yet.
 */
class TypedToolRefsTest {

    @Test
    fun `typed tool refs produce same toolNames as string form`() {
        val typedAgent = agent<String, String>("typed-form") {
            lateinit var fetch: Tool<Map<String, Any?>, Any?>
            lateinit var compile: Tool<Map<String, Any?>, Any?>
            tools {
                fetch = tool("fetch", "Fetch") { _ -> "fetched" }
                compile = tool("compile", "Compile") { _ -> "compiled" }
            }
            skills {
                skill<String, String>("build") {
                    tools(fetch, compile)
                }
            }
        }

        val stringAgent = agent<String, String>("string-form") {
            tools {
                tool("fetch", "Fetch") { _ -> "fetched" }
                tool("compile", "Compile") { _ -> "compiled" }
            }
            skills {
                skill<String, String>("build") {
                    tools("fetch", "compile")
                }
            }
        }

        val typedSkill = typedAgent.skills["build"]!!
        val stringSkill = stringAgent.skills["build"]!!

        assertEquals(stringSkill.toolNames, typedSkill.toolNames)
        assertEquals(true, typedSkill.isAgentic)
        assertEquals(listOf("fetch", "compile"), typedSkill.toolNames)
    }

    @Test
    fun `typed refs survive validate() — agent constructs without unknown-tool error`() {
        val a = agent<String, String>("typed-validate") {
            lateinit var ping: Tool<Map<String, Any?>, Any?>
            tools {
                ping = tool("ping", "Ping") { _ -> "pong" }
            }
            skills {
                skill<String, String>("respond") {
                    tools(ping)
                }
            }
        }
        assertEquals(listOf("ping"), a.skills["respond"]!!.toolNames)
    }
}
