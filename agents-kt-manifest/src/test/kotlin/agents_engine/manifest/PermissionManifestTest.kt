package agents_engine.manifest

import agents_engine.composition.pipeline.then
import agents_engine.core.AgentRuntimeContext
import agents_engine.core.MemoryBank
import agents_engine.core.ToolRisk
import agents_engine.core.agent
import agents_engine.mcp.McpServer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PermissionManifestTest {
    @Test
    fun `agent manifest is deterministic, masks secrets, and attaches hash to runtime context`() {
        var observedHash: String? = null
        val reviewer = agent<String, String>("document-reviewer") {
            prompt("Review uploaded documents for policy problems.")
            model {
                openai("gpt-4o-mini")
                apiKey = "sk-live-secret-value"
                openAiBaseUrl = "https://llm-gateway.example/v1"
                temperature = 0.2
                maxTokens = 2048
            }
            budget {
                maxTurns = 4
                maxToolCalls = 7
                maxDuration = 30.seconds
                perToolTimeout = 3.seconds
                maxTokens = 12_000
                maxConsecutiveSameTool = 2
            }
            memory(MemoryBank())
            tools {
                tool("readUploadedDocument") {
                    description("Read an uploaded document from the review bucket.")
                    policy {
                        risk = ToolRisk.Medium
                        filesystem {
                            read("/uploads/**")
                            writeNone()
                        }
                        network { denyAll() }
                        environment { denyAll() }
                    }
                    executor { "document body" }
                }
            }
            skills {
                skill<String, String>("review", "Review one uploaded document") {
                    useMemory()
                    knowledge("policy", "Internal handling policy") { "Never expose raw customer text." }
                    @Suppress("DEPRECATION")
                    tools("readUploadedDocument")
                    implementedBy {
                        observedHash = AgentRuntimeContext.currentOrNew().manifestHash
                        it.uppercase()
                    }
                }
            }
        }

        val manifest = reviewer.permissionManifest {
            includeProviderConfig = true
            includeBudgets = true
            includeMemory = true
            includePolicy = true
        }

        val firstJson = manifest.toJson()
        val secondJson = reviewer.permissionManifest {
            includeProviderConfig = true
            includeBudgets = true
            includeMemory = true
            includePolicy = true
        }.toJson()

        assertEquals(firstJson, secondJson)
        assertTrue(manifest.sha256.matches(Regex("[a-f0-9]{64}")))
        assertContains(firstJson, "\"agentsKtManifestVersion\":1")
        assertContains(firstJson, "\"manifestSha256\":\"${manifest.sha256}\"")
        assertContains(firstJson, "\"apiKey\":\"masked\"")
        assertContains(firstJson, "\"apiKeyPresent\":true")
        assertContains(firstJson, "\"risk\":\"medium\"")
        assertContains(firstJson, "\"memory\":{\"enabled\":true")
        assertContains(firstJson, "\"filesystem\":{\"read\":{\"globs\":[\"/uploads/**\"],\"mode\":\"globs\"")
        assertFalse(firstJson.contains("sk-live-secret-value"))

        reviewer("hello")
        assertEquals(manifest.sha256, observedHash)
    }

    @Test
    fun `deepseek provider is recorded with masked credentials and base url`() {
        val a = agent<String, String>("deepseek-agent") {
            model {
                deepseek("deepseek-v4-flash")
                apiKey = "sk-deepseek-live-secret"
                deepSeekBaseUrl = "https://deepseek-gateway.example"
            }
            skills {
                skill<String, String>("echo", "Echo input") { implementedBy { it } }
            }
        }

        val json = a.permissionManifest {
            includeProviderConfig = true
        }.toJson()

        assertContains(json, "\"provider\":\"deepseek\"")
        assertContains(json, "\"model\":\"deepseek-v4-flash\"")
        assertContains(json, "\"baseUrl\":\"https://deepseek-gateway.example\"")
        assertContains(json, "\"apiKey\":\"masked\"")
        assertFalse(json.contains("sk-deepseek-live-secret"))
    }

    @Test
    fun `pipeline manifest records composition and writes byte-identical files across runs`() {
        val loader = agent<String, String>("loader") {
            skills {
                skill<String, String>("load") { implementedBy { it.trim() } }
            }
        }
        val summarizer = agent<String, String>("summarizer") {
            skills {
                skill<String, String>("summarize") { implementedBy { it.take(12) } }
            }
        }
        val pipeline = loader then summarizer

        val manifest = pipeline.permissionManifest {
            includeComposition = true
        }
        val jsonA = File.createTempFile("agents-kt-manifest-a", ".json")
        val jsonB = File.createTempFile("agents-kt-manifest-b", ".json")
        val yamlA = File.createTempFile("agents-kt-manifest-a", ".yaml")
        val yamlB = File.createTempFile("agents-kt-manifest-b", ".yaml")

        manifest.writeJson(jsonA)
        pipeline.permissionManifest { includeComposition = true }.writeJson(jsonB)
        manifest.writeYaml(yamlA)
        pipeline.permissionManifest { includeComposition = true }.writeYaml(yamlB)

        assertEquals(jsonA.readText(), jsonB.readText())
        assertEquals(yamlA.readText(), yamlB.readText())
        assertContains(jsonA.readText(), "\"composition\":{\"edges\":[")
        assertContains(jsonA.readText(), "\"type\":\"pipeline\"")
        assertContains(jsonA.readText(), "\"edges\":[{\"from\":\"loader\",\"to\":\"summarizer\",\"type\":\"then\"}]")
        assertContains(yamlA.readText(), "agentsKtManifestVersion: 1")
        assertContains(yamlA.readText(), "manifestSha256: \"${manifest.sha256}\"")
    }

    @Test
    fun `manifest verification flags high risk boundary widening`() {
        val baseline = agent<String, String>("ops") {
            tools {
                tool("syncTicket") {
                    policy {
                        risk = ToolRisk.Low
                        network { denyAll() }
                        filesystem { writeNone() }
                    }
                    executor { "ok" }
                }
            }
            skills {
                skill<String, String>("sync") {
                    @Suppress("DEPRECATION")
                    tools("syncTicket")
                    implementedBy { it }
                }
            }
        }.permissionManifest()

        val widened = agent<String, String>("ops") {
            tools {
                tool("syncTicket") {
                    policy {
                        risk = ToolRisk.High
                        network { allowAll() }
                        filesystem { write("/var/tickets/**") }
                    }
                    executor { "ok" }
                }
            }
            skills {
                skill<String, String>("sync") {
                    @Suppress("DEPRECATION")
                    tools("syncTicket")
                    implementedBy { it }
                }
            }
        }.permissionManifest()

        val result = widened.verifyAgainst(baseline)

        assertFalse(result.ok)
        assertTrue(result.findings.any { it.code == "tool.risk.increased" })
        assertTrue(result.findings.any { it.code == "tool.network.widened" })
        assertTrue(result.findings.any { it.code == "tool.filesystem.write.widened" })
    }

    // --- verifier hardening: set comparison, not coarse scoring (0.7.1) ---

    private fun opsManifest(policy: agents_engine.core.ToolPolicyBuilder.() -> Unit) =
        agent<String, String>("ops") {
            tools {
                tool("syncTicket") {
                    policy(policy)
                    executor { "ok" }
                }
            }
            skills {
                skill<String, String>("sync") {
                    @Suppress("DEPRECATION")
                    tools("syncTicket")
                    implementedBy { it }
                }
            }
        }.permissionManifest()

    @Test fun `verify catches a host added within the same hosts mode (set, not score)`() {
        val baseline = opsManifest { network { allow("api.internal") } }
        val current = opsManifest { network { allow("api.internal"); allow("evil.example") } }
        val result = current.verifyAgainst(baseline)
        assertFalse(result.ok, "adding a host must be a widening")
        assertTrue(result.findings.any { it.code == "tool.network.widened" }, result.findings.toString())
    }

    @Test fun `verify catches a broader write glob even when the glob count is unchanged`() {
        val baseline = opsManifest { filesystem { write("/srv/uploads/**") } }
        val current = opsManifest { filesystem { write("/**") } }
        val result = current.verifyAgainst(baseline)
        assertFalse(result.ok, "replacing a narrow glob with /** must be a widening")
        assertTrue(result.findings.any { it.code == "tool.filesystem.write.widened" }, result.findings.toString())
    }

    @Test fun `verify does not flag a pure narrowing of write globs`() {
        val baseline = opsManifest { filesystem { write("/a/**"); write("/b/**") } }
        val current = opsManifest { filesystem { write("/a/**") } }
        val result = current.verifyAgainst(baseline)
        assertTrue(result.ok, "removing a glob is a narrowing, not a widening: ${result.findings}")
    }

    @Test fun `verify catches an env var added to the allow-list`() {
        val baseline = opsManifest { environment { allow("HOME") } }
        val current = opsManifest { environment { allow("HOME"); allow("AWS_SECRET_ACCESS_KEY") } }
        val result = current.verifyAgainst(baseline)
        assertFalse(result.ok, "exposing another env var must be a widening")
        assertTrue(result.findings.any { it.code == "tool.environment.widened" }, result.findings.toString())
    }

    @Test fun `verify keys tools by agent so a widening is not hidden by a same-named tool elsewhere`() {
        fun netAgent(name: String, net: agents_engine.core.ToolNetworkPolicyBuilder.() -> Unit) =
            agent<String, String>(name) {
                tools {
                    tool("syncTicket") {
                        policy { network(net) }
                        executor { "ok" }
                    }
                }
                skills {
                    skill<String, String>("s") {
                        @Suppress("DEPRECATION")
                        tools("syncTicket")
                        implementedBy { it }
                    }
                }
            }
        // Both agents have a tool named "syncTicket"; only "writer"'s widens. The old
        // name-only keying kept "reader"'s tool and hid the writer's widening.
        val baseline = (netAgent("reader") { denyAll() } then netAgent("writer") { denyAll() }).permissionManifest()
        val current = (netAgent("reader") { denyAll() } then netAgent("writer") { allowAll() }).permissionManifest()
        val result = current.verifyAgainst(baseline)
        assertFalse(result.ok, "the writer's widening must not be hidden by reader's same-named tool")
        assertTrue(
            result.findings.any { it.code == "tool.network.widened" && "writer" in it.path },
            result.findings.toString(),
        )
    }

    @Test
    fun `mcp server manifest records exposed server capabilities`() {
        val echo = agent<String, String>("echo") {
            skills {
                skill<String, String>("echo", "Echo input") { implementedBy { it } }
            }
        }
        val server = McpServer.from(echo) {
            expose("echo")
        }

        val json = server.permissionManifest().toJson()

        assertContains(json, "\"subject\":{\"agents\":[\"echo\"],\"type\":\"mcp-server\"}")
        assertContains(json, "\"mcpServers\":[")
        assertContains(json, "\"capabilities\":{\"completions\":false,\"experimental\":{},\"logging\":false,\"tools\":{\"listChanged\":false}")
        assertContains(json, "\"tools\":[{\"description\":\"Echo input\"")
    }
}
