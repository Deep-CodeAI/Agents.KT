package agents_engine.composition.branch

import agents_engine.composition.pipeline.then
import agents_engine.core.agent
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MODEL = "gpt-oss:120b-cloud"
private const val HOST  = "localhost"
private const val PORT  = 11434

class BranchAgenticIntegrationTest {

    sealed interface Sentiment
    data class Positive(val text: String) : Sentiment
    data class Negative(val text: String) : Sentiment
    data class Neutral(val text: String) : Sentiment

    // --- Mock LLM ---

    @Test
    fun `agentic classifier routes to correct branch handler`() {
        val mock = ModelClient { msgs ->
            val input = msgs.last { it.role == "user" }.content
            when {
                input.contains("great") -> LlmResponse.Text("positive")
                input.contains("bad")   -> LlmResponse.Text("negative")
                else                     -> LlmResponse.Text("neutral")
            }
        }

        val classifier = agent<String, Sentiment>("classifier") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, Sentiment>("classify", "Classify sentiment") {
                tools()
                transformOutput { raw ->
                    val text = raw.trim().lowercase()
                    when {
                        text.contains("positive") -> Positive(text)
                        text.contains("negative") -> Negative(text)
                        else                       -> Neutral(text)
                    }
                }
            }}
        }

        val branch = classifier.branch {
            on<Positive>() then agent<Positive, String>("pos") {
                skills { skill<Positive, String>("pos", "Handle positive") { implementedBy { "😊 ${it.text}" } } }
            }
            on<Negative>() then agent<Negative, String>("neg") {
                skills { skill<Negative, String>("neg", "Handle negative") { implementedBy { "😟 ${it.text}" } } }
            }
            on<Neutral>() then agent<Neutral, String>("neu") {
                skills { skill<Neutral, String>("neu", "Handle neutral") { implementedBy { "😐 ${it.text}" } } }
            }
        }

        assertEquals("😊 positive", branch("This is great"))
        assertEquals("😟 negative", branch("This is bad"))
        assertEquals("😐 neutral", branch("This is okay"))
    }

    @Test
    fun `agentic classifier with agentic branch handlers`() {
        val classifierMock = ModelClient { _ -> LlmResponse.Text("positive") }
        val handlerMock = ModelClient { _ -> LlmResponse.Text("Glad to hear it!") }

        val classifier = agent<String, Sentiment>("classifier") {
            model { ollama("llama3"); client = classifierMock }
            skills { skill<String, Sentiment>("classify", "Classify sentiment") {
                tools()
                transformOutput { Positive(it.trim()) }
            }}
        }

        val branch = classifier.branch {
            on<Positive>() then agent<Positive, String>("pos") {
                model { ollama("llama3"); client = handlerMock }
                skills { skill<Positive, String>("respond", "Respond to positive") { tools() } }
            }
            on<Negative>() then agent<Negative, String>("neg") {
                skills { skill<Negative, String>("neg", "Handle negative") { implementedBy { "sad" } } }
            }
            on<Neutral>() then agent<Neutral, String>("neu") {
                skills { skill<Neutral, String>("neu", "Handle neutral") { implementedBy { "meh" } } }
            }
        }

        assertEquals("Glad to hear it!", branch("wonderful"))
    }

    @Test
    fun `branch handler with tool-calling agentic agent`() {
        val classifierMock = ModelClient { _ -> LlmResponse.Text("negative") }

        val handlerResponses = ArrayDeque<LlmResponse>()
        handlerResponses.add(LlmResponse.ToolCalls(listOf(ToolCall("log_issue", mapOf("msg" to "user unhappy")))))
        handlerResponses.add(LlmResponse.Text("Issue logged"))
        val handlerMock = ModelClient { _ -> handlerResponses.removeFirst() }

        val toolCalls = mutableListOf<String>()

        val classifier = agent<String, Sentiment>("classifier") {
            model { ollama("llama3"); client = classifierMock }
            skills { skill<String, Sentiment>("classify", "Classify") {
                tools()
                transformOutput { Negative(it.trim()) }
            }}
        }

        val branch = classifier.branch {
            on<Positive>() then agent<Positive, String>("pos") {
                skills { skill<Positive, String>("pos", "Pos") { implementedBy { "ok" } } }
            }
            on<Negative>() then agent<Negative, String>("neg") {
                model { ollama("llama3"); client = handlerMock }
                tools { tool("log_issue", "Log a support issue") { args -> toolCalls.add(args["msg"].toString()); "logged" } }
                skills { skill<Negative, String>("neg", "Handle negative with logging") { tools("log_issue") } }
            }
            on<Neutral>() then agent<Neutral, String>("neu") {
                skills { skill<Neutral, String>("neu", "Neu") { implementedBy { "meh" } } }
            }
        }

        assertEquals("Issue logged", branch("terrible"))
        assertEquals(listOf("user unhappy"), toolCalls)
    }

    @Test
    fun `onSkillChosen fires for classifier and branch handler`() {
        val chosen = mutableListOf<String>()
        val mock = ModelClient { _ -> LlmResponse.Text("pos") }

        val classifier = agent<String, Sentiment>("classifier") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, Sentiment>("classify", "Classify") {
                tools()
                transformOutput { Positive(it.trim()) }
            }}
            onSkillChosen { chosen.add(it) }
        }

        val branch = classifier.branch {
            on<Positive>() then agent<Positive, String>("pos") {
                skills { skill<Positive, String>("handle-pos", "Pos") { implementedBy { "good" } } }
                onSkillChosen { chosen.add(it) }
            }
            on<Negative>() then agent<Negative, String>("neg") {
                skills { skill<Negative, String>("handle-neg", "Neg") { implementedBy { "bad" } } }
            }
            on<Neutral>() then agent<Neutral, String>("neu") {
                skills { skill<Neutral, String>("handle-neu", "Neu") { implementedBy { "meh" } } }
            }
        }

        branch("happy")
        assertEquals(listOf("classify", "handle-pos"), chosen)
    }

    @Test
    fun `agentic branch composable in pipeline`() {
        val classifierMock = ModelClient { _ -> LlmResponse.Text("positive") }

        val preparer = agent<String, String>("preparer") {
            skills { skill<String, String>("prep", "Prepare input") {
                implementedBy { it.lowercase().trim() }
            }}
        }

        val classifier = agent<String, Sentiment>("classifier") {
            model { ollama("llama3"); client = classifierMock }
            skills { skill<String, Sentiment>("classify", "Classify") {
                tools()
                transformOutput { Positive(it.trim()) }
            }}
        }

        val branch = classifier.branch {
            on<Positive>() then agent<Positive, Int>("pos") {
                skills { skill<Positive, Int>("pos", "Pos") { implementedBy { it.text.length } } }
            }
            on<Negative>() then agent<Negative, Int>("neg") {
                skills { skill<Negative, Int>("neg", "Neg") { implementedBy { -1 } } }
            }
            on<Neutral>() then agent<Neutral, Int>("neu") {
                skills { skill<Neutral, Int>("neu", "Neu") { implementedBy { 0 } } }
            }
        }

        val pipeline = preparer then branch
        val result = pipeline("  HAPPY  ")
        assertEquals(8, result) // "positive".length
    }

    @Test
    fun `branch with skill selection on classifier`() {
        val chosen = mutableListOf<String>()

        val classifier = agent<String, Sentiment>("classifier") {
            skills {
                skill<String, Sentiment>("quick-classify", "Fast keyword check") {
                    implementedBy { input ->
                        when {
                            input.contains("good") -> Positive(input)
                            input.contains("bad")  -> Negative(input)
                            else                    -> Neutral(input)
                        }
                    }
                }
                skill<String, Sentiment>("deep-classify", "Deep analysis") {
                    implementedBy { Neutral(text = it) }
                }
            }
            skillSelection { input -> if (input.length < 20) "quick-classify" else "deep-classify" }
            onSkillChosen { chosen.add(it) }
        }

        val branch = classifier.branch {
            on<Positive>() then agent<Positive, String>("pos") {
                skills { skill<Positive, String>("pos", "Pos") { implementedBy { "positive: ${it.text}" } } }
            }
            on<Negative>() then agent<Negative, String>("neg") {
                skills { skill<Negative, String>("neg", "Neg") { implementedBy { "negative: ${it.text}" } } }
            }
            on<Neutral>() then agent<Neutral, String>("neu") {
                skills { skill<Neutral, String>("neu", "Neu") { implementedBy { "neutral: ${it.text}" } } }
            }
        }

        assertEquals("positive: good day", branch("good day"))
        assertEquals("quick-classify", chosen.single())
    }

    // --- Real LLM integration ---

    @Test
    fun `branch with real LLM classifier`() {
        val chosen = mutableListOf<String>()

        val classifier = agent<String, Sentiment>("classifier") {
            prompt("Classify the sentiment of the input as exactly one word: positive, negative, or neutral. Reply with ONLY that word.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, Sentiment>("classify", "Classify text sentiment") {
                tools()
                transformOutput { raw ->
                    val word = raw.trim().lowercase()
                    when {
                        word.contains("positive") -> Positive(word)
                        word.contains("negative") -> Negative(word)
                        else                       -> Neutral(word)
                    }
                }
            }}
            onSkillChosen { chosen.add(it) }
        }

        val branch = classifier.branch {
            on<Positive>() then agent<Positive, String>("pos") {
                skills { skill<Positive, String>("pos", "Respond positively") { implementedBy { "Thanks for the kind words!" } } }
            }
            on<Negative>() then agent<Negative, String>("neg") {
                skills { skill<Negative, String>("neg", "Respond to complaint") { implementedBy { "Sorry to hear that." } } }
            }
            on<Neutral>() then agent<Neutral, String>("neu") {
                skills { skill<Neutral, String>("neu", "Acknowledge") { implementedBy { "Noted." } } }
            }
        }

        val result = branch("I love this product, it's amazing!")
        println("Sentiment branch result: $result")
        assertEquals("Thanks for the kind words!", result)
        assertEquals(listOf("classify"), chosen)
    }

    // --- Full agentic branching pipeline ---

    sealed interface Edibility
    data class Edible(val item: String) : Edibility
    data class NotEdible(val item: String) : Edibility

    @Test
    fun `edible or not branching pipeline with real LLM`() {
        val words = listOf("wood", "apple", "rock", "scissors", "tomato", "bread")
        val routes = mutableMapOf<String, String>()

        for (word in words) {
            val classifier = agent<String, Edibility>("classifier-$word") {
                prompt("Determine if the given item is edible (food) or not. Reply with ONLY one word: edible or inedible.")
                model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
                skills { skill<String, Edibility>("classify", "Classify if item is edible") {
                    tools()
                    transformOutput { raw ->
                        if (raw.trim().lowercase().contains("edible") && !raw.trim().lowercase().contains("inedible"))
                            Edible(word)
                        else
                            NotEdible(word)
                    }
                }}
            }

            val branch = classifier.branch {
                on<Edible>() then agent<Edible, String>("chef-$word") {
                    prompt("You are a creative chef. Given a food item, suggest a dish. Reply with ONLY: Yummy, we can make {dish name} from it")
                    model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
                    skills { skill<Edible, String>("cook", "Suggest a dish") { tools() } }
                }
                on<NotEdible>() then agent<NotEdible, String>("crafter-$word") {
                    prompt("You are a creative crafter. Given a non-food item, suggest what to make from it. Reply with ONLY: Don't eat this! Better {what to make from it}")
                    model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
                    skills { skill<NotEdible, String>("craft", "Suggest a craft") { tools() } }
                }
            }

            val result = branch(word)
            routes[word] = result
            println("  $word → $result")
        }

        // Edible items should get "Yummy" responses
        for (food in listOf("apple", "tomato", "bread")) {
            assertTrue(routes[food]!!.contains("Yummy", ignoreCase = true),
                "Expected 'Yummy' for $food, got: ${routes[food]}")
        }

        // Non-edible items should get "Don't eat" responses
        for (thing in listOf("wood", "rock", "scissors")) {
            assertTrue(routes[thing]!!.contains("Don't eat", ignoreCase = true),
                "Expected 'Don't eat' for $thing, got: ${routes[thing]}")
        }
    }
}
