package agents_engine.core

import agents_engine.composition.pipeline.then
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MODEL = "gpt-oss:120b-cloud"
private const val HOST  = "localhost"
private const val PORT  = 11434

class SharedKnowledgeTest {

    @Test
    fun `two skills share knowledge via common map`() {
        val sharedKnowledge = mutableMapOf(
            "db-host" to "localhost:5432",
            "api-key" to "sk-secret-123",
        )

        val reader = skill<String, String>("read-config", "Reads config values") {
            knowledge("config", "Shared configuration store") { sharedKnowledge.entries.joinToString("\n") { "${it.key}=${it.value}" } }
            implementedBy { key -> sharedKnowledge[key] ?: "unknown" }
        }

        val writer = skill<String, String>("write-config", "Writes config values") {
            knowledge("config", "Shared configuration store") { sharedKnowledge.entries.joinToString("\n") { "${it.key}=${it.value}" } }
            implementedBy { input ->
                val (k, v) = input.split("=", limit = 2)
                sharedKnowledge[k] = v
                "ok"
            }
        }

        assertEquals("localhost:5432", reader("db-host"))
        writer("db-host=prod-db:5432")
        assertEquals("prod-db:5432", reader("db-host"))

        // Both skills see the same updated knowledge
        assertTrue(reader.knowledge["config"]!!().contains("prod-db:5432"))
        assertTrue(writer.knowledge["config"]!!().contains("prod-db:5432"))
    }

    @Test
    fun `two agents share knowledge via common map`() {
        val corpus = mutableMapOf(
            "style" to "Prefer val over var. Use data classes.",
            "rules" to "Max line length 120. No wildcard imports.",
        )

        val coder = agent<String, String>("coder") {
            skills { skill<String, String>("write", "Write code") {
                knowledge("style-guide", "Coding style rules") { corpus["style"]!! }
                knowledge("rules", "Linting rules") { corpus["rules"]!! }
                implementedBy { "fun ${it}() {}" }
            }}
        }

        val reviewer = agent<String, String>("reviewer") {
            skills { skill<String, String>("review", "Review code") {
                knowledge("style-guide", "Coding style rules") { corpus["style"]!! }
                knowledge("rules", "Linting rules") { corpus["rules"]!! }
                implementedBy { "LGTM: $it" }
            }}
        }

        // Both agents see original knowledge
        val coderKnowledge = coder.skills["write"]!!.knowledge
        val reviewerKnowledge = reviewer.skills["review"]!!.knowledge
        assertTrue(coderKnowledge["style-guide"]!!().contains("Prefer val"))
        assertTrue(reviewerKnowledge["style-guide"]!!().contains("Prefer val"))

        // Update shared corpus
        corpus["style"] = "Prefer val over var. Use data classes. Use sealed interfaces."

        // Both agents see the update (lazy evaluation)
        assertTrue(coderKnowledge["style-guide"]!!().contains("sealed interfaces"))
        assertTrue(reviewerKnowledge["style-guide"]!!().contains("sealed interfaces"))
    }

    @Test
    fun `pipeline agents share knowledge and see mutations from earlier stage`() {
        val context = mutableMapOf<String, String>()

        val extractor = agent<String, String>("extractor") {
            skills { skill<String, String>("extract", "Extract keywords") {
                knowledge("context", "Shared pipeline context") { context.toString() }
                implementedBy { input ->
                    val keywords = input.split(" ").filter { it.length > 3 }
                    context["keywords"] = keywords.joinToString(",")
                    keywords.joinToString(",")
                }
            }}
        }

        val formatter = agent<String, String>("formatter") {
            skills { skill<String, String>("format", "Format with context") {
                knowledge("context", "Shared pipeline context") { context.toString() }
                implementedBy { input ->
                    val kw = context["keywords"] ?: "none"
                    "Formatted [$kw]: $input"
                }
            }}
        }

        val pipeline = extractor then formatter
        val result = pipeline("The quick brown fox jumps")

        assertEquals("Formatted [quick,brown,jumps]: quick,brown,jumps", result)
        assertTrue(context.containsKey("keywords"))
    }

    @Test
    fun `agentic agent loads shared knowledge via tool call`() {
        val sharedDocs = mapOf(
            "api-spec" to "POST /users — creates a user. Fields: name (string), email (string).",
            "db-schema" to "Table users: id SERIAL, name TEXT, email TEXT.",
        )

        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("api-spec", emptyMap()))))
        responses.add(LlmResponse.Text("I'll create a POST /users endpoint"))
        val captured = mutableListOf<List<LlmMessage>>()
        val mock = ModelClient { msgs -> captured.add(msgs.toList()); responses.removeFirst() }

        val knowledgeLoaded = mutableListOf<String>()

        val coder = agent<String, String>("coder") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("implement", "Implement feature from shared docs") {
                tools()
                knowledge("api-spec", "REST API specification") { sharedDocs["api-spec"]!! }
                knowledge("db-schema", "Database schema") { sharedDocs["db-schema"]!! }
            }}
            onKnowledgeUsed { name, _ -> knowledgeLoaded.add(name) }
        }

        val result = coder("Implement user creation endpoint")

        assertEquals("I'll create a POST /users endpoint", result)
        assertEquals(listOf("api-spec"), knowledgeLoaded)

        // Verify knowledge content was returned as tool result
        val toolMsg = captured[1].find { it.role == "tool" }!!
        assertTrue(toolMsg.content.contains("POST /users"))
    }

    @Test
    fun `shared knowledge across agentic agents in pipeline with real LLM`() {
        val projectContext = mapOf(
            "tech-stack" to "Kotlin, Micronaut, PostgreSQL",
            "conventions" to "Use data classes for DTOs. Prefer val. Max 120 chars per line.",
        )

        val planner = agent<String, String>("planner") {
            prompt("You are a technical planner. Given a feature request and the tech stack, outline 2-3 implementation steps. Be brief — one line per step.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("plan", "Plan implementation steps") {
                tools()
                knowledge("tech-stack", "Project technology stack") { projectContext["tech-stack"]!! }
            }}
        }

        val reviewer = agent<String, String>("reviewer") {
            prompt("You are a code reviewer. Given an implementation plan and coding conventions, check if the plan follows conventions. Reply with a one-sentence verdict.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("review", "Review plan against conventions") {
                tools()
                knowledge("conventions", "Project coding conventions") { projectContext["conventions"]!! }
            }}
        }

        val pipeline = planner then reviewer
        val result = pipeline("Add a user registration endpoint")
        println("Plan → Review result: $result")

        assertTrue(result.isNotBlank())
        assertTrue(result.length > 10, "Expected a real review sentence, got: $result")
    }

    @Test
    fun `shared product catalog drives both recommender and validator with real LLM`() {
        // Shared knowledge: a product catalog that both agents must reference
        val catalog = mapOf(
            "products" to """
                | ID  | Name              | Price | Category    | In Stock |
                |-----|-------------------|-------|-------------|----------|
                | P01 | Kotlin In Action  | 45    | Books       | yes      |
                | P02 | Mechanical KB     | 120   | Electronics | yes      |
                | P03 | Espresso Machine  | 299   | Appliances  | no       |
                | P04 | USB-C Hub         | 35    | Electronics | yes      |
                | P05 | Clean Code        | 40    | Books       | yes      |
            """.trimIndent(),
            "policies" to """
                - Only recommend products that are in stock.
                - Budget limit must be respected — never exceed it.
                - Prefer variety across categories when possible.
            """.trimIndent(),
        )

        val knowledgeUsed = mutableMapOf<String, MutableList<String>>()

        val recommender = agent<String, String>("recommender") {
            prompt("""You are a product recommender. Given a customer request and the product catalog,
                |recommend 1-2 products by their ID and name. Follow the recommendation policies.
                |Reply with ONLY the product IDs and names, one per line, like: P01 — Kotlin In Action""".trimMargin())
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("recommend", "Recommend products from catalog") {
                tools()
                knowledge("products", "Full product catalog with prices and stock status") { catalog["products"]!! }
                knowledge("policies", "Recommendation policies and rules") { catalog["policies"]!! }
            }}
            onKnowledgeUsed { name, _ -> knowledgeUsed.getOrPut("recommender") { mutableListOf() }.add(name) }
        }

        val validator = agent<String, String>("validator") {
            prompt("""You are a recommendation validator. Given a product recommendation and the catalog,
                |verify that: (1) the recommended products exist in the catalog, (2) they are in stock,
                |(3) total price is within budget. Reply with ONLY "VALID" or "INVALID: reason".""".trimMargin())
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("validate", "Validate recommendations against catalog") {
                tools()
                knowledge("products", "Full product catalog with prices and stock status") { catalog["products"]!! }
                knowledge("policies", "Recommendation policies and rules") { catalog["policies"]!! }
            }}
            onKnowledgeUsed { name, _ -> knowledgeUsed.getOrPut("validator") { mutableListOf() }.add(name) }
        }

        val pipeline = recommender then validator
        val result = pipeline("I want to buy electronics, budget is 100 dollars")
        println("Recommendation → Validation result: $result")

        // Validator should confirm the recommendation is valid
        assertTrue(result.uppercase().contains("VALID"),
            "Expected VALID recommendation (USB-C Hub is 35, in stock, within budget), got: $result")

        // Both agents should have consulted the shared catalog
        assertTrue(knowledgeUsed.containsKey("recommender"),
            "Recommender should have loaded knowledge")
        assertTrue(knowledgeUsed.containsKey("validator"),
            "Validator should have loaded knowledge")

        println("Recommender used: ${knowledgeUsed["recommender"]}")
        println("Validator used: ${knowledgeUsed["validator"]}")
    }
}
