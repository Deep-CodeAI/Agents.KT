package agents_engine.model

import agents_engine.core.KnowledgeRetriever
import agents_engine.core.agent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #3863 — query-aware knowledge entries surface as tools with a {query}
// parameter, route the model's query into the suspend retriever, and stay
// out of the inlined prompt knowledge dump.

class KnowledgeRetrieverToolTest {

    private fun testAgent(retriever: KnowledgeRetriever) = agent<String, String>("docs-agent") {
        skills {
            skill<String, String>("answer", "Answers from docs") {
                knowledge("project-docs", "Project documentation", retriever)
                implementedBy { it }
            }
        }
    }

    @Test
    fun `retriever knowledge becomes a query-arg tool def with a suspend session path`() = runTest {
        val seen = mutableListOf<String>()
        val a = testAgent { query -> seen.add(query); "retrieved for: $query" }
        val skill = a.skills.values.single()

        val resolved = resolveAllowedTools(a, skill)
        val toolDef = resolved.knowledgeToolDefs.single()

        assertEquals("project-docs", toolDef.name)
        val schema = toolDef.parametersSchemaJson ?: error("query-aware knowledge must advertise a schema")
        assertTrue("\"query\"" in schema && "\"required\"" in schema, "schema must require query; got: $schema")

        // Blocking path bridges into the retriever.
        assertEquals("retrieved for: budget caps", toolDef.executor(mapOf("query" to "budget caps")))
        // Session path is natively suspend.
        val viaSession = toolDef.sessionExecutor!!.invoke(mapOf("query" to "routing")) { }
        assertEquals("retrieved for: routing", viaSession)
        assertEquals(listOf("budget caps", "routing"), seen)
    }

    @Test
    fun `static knowledge entries keep the no-arg shape`() {
        val a = agent<String, String>("static-agent") {
            skills {
                skill<String, String>("answer", "Answers") {
                    knowledge("faq", "Frequently asked") { "static content" }
                    implementedBy { it }
                }
            }
        }
        val skill = a.skills.values.single()
        val toolDef = resolveAllowedTools(a, skill).knowledgeToolDefs.single()

        assertEquals(null, toolDef.parametersSchemaJson, "static knowledge stays a no-arg tool")
        assertEquals("static content", toolDef.executor(emptyMap()))
    }

    @Test
    fun `prompt context marks retriever entries on-demand instead of inlining`() {
        val a = testAgent { "SHOULD NOT BE INLINED" }
        val context = a.skills.values.single().toLlmContext()

        assertTrue("on-demand" in context, "retriever entry must be marked on-demand; got: $context")
        assertTrue("SHOULD NOT BE INLINED" !in context, "retriever content must not be inlined; got: $context")
    }
}
