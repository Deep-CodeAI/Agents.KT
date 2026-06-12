package agents_engine.model

import agents_engine.core.ToolPolicyViolation
import agents_engine.core.agent
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #2889 — the (args, env) executor shape: policy-gated env operations,
// per-call construction, single-arg back-compat pinned.

class ToolEnvironmentExecutorTest {

    @Test
    fun `env executor reads and writes inside the declared globs`() {
        val dir = Files.createTempDirectory("env-exec").toRealPath()
        val input = dir.resolve("in.txt").also { Files.writeString(it, "payload") }

        val a = agent<String, String>("worker") {
            tools {
                tool("copy") {
                    policy {
                        filesystem { read("$dir/**"); write("$dir/**") }
                    }
                    executor { args, env ->
                        val content = env.readText(args["from"].toString())
                        env.writeText(args["to"].toString(), content.uppercase())
                        "copied"
                    }
                }
            }
            skills {
                skill<String, String>("run", "Runs") {
                    @Suppress("DEPRECATION")
                    tools("copy")
                    implementedBy { it }
                }
            }
        }

        val tool = a.toolMap.getValue("copy")
        val out = dir.resolve("out.txt")
        assertEquals("copied", tool.executor(mapOf("from" to input.toString(), "to" to out.toString())))
        assertEquals("PAYLOAD", out.readText())
    }

    @Test
    fun `env operations outside the declared policy throw ToolPolicyViolation before acting`() {
        val dir = Files.createTempDirectory("env-deny").toRealPath()
        val a = agent<String, String>("worker") {
            tools {
                tool("escape") {
                    policy { filesystem { read("$dir/**"); writeNone() } }
                    executor { _, env ->
                        env.writeText("/tmp/forbidden-${System.nanoTime()}.txt", "x")
                    }
                }
            }
            skills {
                skill<String, String>("run", "Runs") {
                    @Suppress("DEPRECATION")
                    tools("escape")
                    implementedBy { it }
                }
            }
        }

        val e = assertFailsWith<ToolPolicyViolation> {
            a.toolMap.getValue("escape").executor(emptyMap())
        }
        assertTrue("writeText" in (e.message ?: ""), "violation names the operation; got: ${e.message}")
    }

    @Test
    fun `env variable access requires the declared allow-list`() {
        val a = agent<String, String>("worker") {
            tools {
                tool("env-read") {
                    policy { environment { allow("HOME") } }
                    executor { args, env ->
                        when (args["var"]) {
                            "HOME" -> env.env("HOME") ?: "unset"
                            else -> env.env("SECRET_TOKEN") ?: "unset"
                        }
                    }
                }
            }
            skills {
                skill<String, String>("run", "Runs") {
                    @Suppress("DEPRECATION")
                    tools("env-read")
                    implementedBy { it }
                }
            }
        }

        val tool = a.toolMap.getValue("env-read")
        tool.executor(mapOf("var" to "HOME")) // allowed — must not throw
        assertFailsWith<ToolPolicyViolation> { tool.executor(mapOf("var" to "SECRET_TOKEN")) }
    }

    @Test
    fun `single-arg executors still compile and run — back-compat pinned`() {
        val a = agent<String, String>("legacy") {
            tools {
                @Suppress("DEPRECATION")
                tool("old-shape") {
                    executor { args -> "ok-${args["x"]}" }
                }
            }
            skills {
                skill<String, String>("run", "Runs") {
                    @Suppress("DEPRECATION")
                    tools("old-shape")
                    implementedBy { it }
                }
            }
        }
        assertEquals("ok-1", a.toolMap.getValue("old-shape").executor(mapOf("x" to "1")))
    }
}
