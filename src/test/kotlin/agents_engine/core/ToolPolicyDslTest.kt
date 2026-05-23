package agents_engine.core

import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolPolicyDslTest {

    @Test
    fun `tool builder captures declarative sandbox policy`() {
        val a = agent<String, String>("policy-agent") {
            lateinit var readUploadedDocument: agents_engine.model.Tool<Map<String, Any?>, Any?>
            tools {
                readUploadedDocument = tool("readUploadedDocument") {
                    description("Read a KYC upload")
                    policy {
                        risk = ToolRisk.Medium
                        filesystem {
                            read("/uploads/kyc/**")
                            writeNone()
                        }
                        network { denyAll() }
                        environment { allow("OCR_REGION") }
                    }
                    executor { "ok" }
                }
            }
            skills {
                skill<String, String>("read", "Read uploaded docs") {
                    implementedBy { "ok" }
                    tools(readUploadedDocument)
                }
            }
        }

        val def = assertNotNull(a.toolMap["readUploadedDocument"])
        val policy = assertNotNull(def.policy)
        assertEquals(ToolRisk.MEDIUM, def.risk)
        assertEquals(ToolRisk.MEDIUM, policy.risk)
        assertEquals(ToolFilesystemAccess.Globs(listOf("/uploads/kyc/**")), policy.filesystem.read)
        assertEquals(ToolFilesystemAccess.None, policy.filesystem.write)
        assertEquals(ToolNetworkPolicy.DenyAll, policy.network)
        assertEquals(ToolEnvironmentPolicy.Vars(listOf("OCR_REGION")), policy.environment)
    }

    @Test
    fun `tool policy round trips through manifest json and yaml`() {
        val policy = toolPolicy {
            risk = ToolRisk.High
            filesystem {
                read("/uploads/kyc/**")
                write("/tmp/agents-kt/**")
            }
            network {
                allow("ocr.internal")
                allow("api.example.com")
            }
            environment {
                allow("OCR_REGION")
                allow("TMPDIR")
            }
        }

        assertEquals(policy, ToolPolicy.fromManifestMap(policy.toManifestMap()))
        assertEquals(policy, ToolPolicy.fromManifestJson(policy.toManifestJson()))
        assertEquals(policy, ToolPolicy.fromManifestYaml(policy.toManifestYaml()))
    }

    @Test
    fun `tool policy explicit deny modes round trip through manifest formats`() {
        val policy = toolPolicy {
            risk = ToolRisk.Critical
            filesystem {
                readNone()
                writeNone()
            }
            network { allowAll() }
            environment { denyAll() }
        }

        assertEquals(ToolNetworkPolicy.AllowAll, policy.network)
        assertEquals(policy, ToolPolicy.fromManifestJson(policy.toManifestJson()))
        assertEquals(policy, ToolPolicy.fromManifestYaml(policy.toManifestYaml()))
    }

    @Test
    fun `pipeline tool events expose policy risk and declared capability flag`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(
            LlmResponse.ToolCalls(
                listOf(ToolCall(name = "read_uploaded_document", arguments = mapOf("path" to "/uploads/kyc/a.pdf"))),
            ),
        )
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }
        val events = mutableListOf<PipelineEvent>()

        val a = agent<String, String>("audited") {
            lateinit var read: agents_engine.model.Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools {
                read = tool("read_uploaded_document") {
                    description("Read uploaded KYC document")
                    policy {
                        risk = ToolRisk.High
                        filesystem { read("/uploads/kyc/**") }
                        network { denyAll() }
                        environment { denyAll() }
                    }
                    executor { "pdf text" }
                }
            }
            skills {
                skill<String, String>("read", "Read docs") {
                    tools(read)
                }
            }
        }
        a.observe { events += it }

        a("summarize")

        val toolEvent = events.filterIsInstance<PipelineEvent.ToolCalled>().single()
        assertEquals(ToolRisk.HIGH, toolEvent.toolPolicyRisk)
        assertTrue(toolEvent.usedDeclaredCapability)
    }
}
