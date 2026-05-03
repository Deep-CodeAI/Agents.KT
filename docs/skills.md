[← Back to README](../README.md)

## Skills

An agent is a container of typed skills. Each skill has its own `<IN, OUT>` and a `description` used for docs and LLM routing. At least one skill must produce the agent's `OUT` type — validated at construction.

```kotlin
val writeCode = skill<Specification, CodeBundle>("write-code",
    "Writes production Kotlin code from scratch based on a specification") {
    knowledge("style-guide", "Preferred coding style — immutability, naming, formatting") {
        "Prefer val over var. Use data classes for DTOs."
    }
    knowledge("examples", "Concrete input/output pairs for few-shot prompting") {
        loadExamples("code/greenfield-examples.kt")
    }
    implementedBy { spec -> CodeBundle(generate(spec)) }
}

val coder = agent<Specification, CodeBundle>("coder") {
    prompt("You are an expert Kotlin developer.")
    skills {
        +writeCode                                                          // pre-defined skill
        skill<String, String>("format-code", "Formats Kotlin source") { }  // inline utility skill
    }
}

// Call any skill directly — fully typed, no casts
writeCode.execute(mySpec)   // Specification → CodeBundle
```

### How a skill describes itself to the LLM

```kotlin
skill.toLlmDescription()   // auto-generated markdown — no extra annotations needed
skill.toLlmContext()        // full context: description + all knowledge content loaded
skill.knowledgeTools()      // tools model: knowledge as callable list the LLM pulls on demand
```

**`toLlmDescription()`** — convention-over-configuration. Auto-generated from what's already on the skill — name, types, description, and knowledge index. No `input()`, `output()`, or `rule()` calls needed. When `IN`/`OUT` types carry `@Generable`, their description and field list (with `@Guide` texts) are embedded inline:

```markdown
## Skill: write-code

**Input:** Specification — A structured API specification
  - endpoints (List<String>): List of endpoint paths to implement
**Output:** CodeBundle — A bundle of generated Kotlin source files
  - source (String): The generated Kotlin source code

Writes production Kotlin code from scratch.

**Knowledge:**
- style-guide — Preferred coding style — immutability, naming, formatting
- examples — Concrete input/output pairs for few-shot prompting
```

Override for the rare case where generated text isn't right:

```kotlin
skill<Specification, CodeBundle>("write-code", "...") {
    llmDescription("Custom markdown description")
}
```

**`toLlmContext()`** — everything pre-loaded before the LLM runs: `toLlmDescription()` followed by the full content of each knowledge entry:

```markdown
## Skill: write-code

**Input:** Specification — A structured API specification
  - endpoints (List<String>): List of endpoint paths to implement
**Output:** CodeBundle — A bundle of generated Kotlin source files
  - source (String): The generated Kotlin source code

Writes production Kotlin code from scratch.

**Knowledge:**
- style-guide — Preferred coding style — immutability, naming, formatting
- examples — Concrete input/output pairs for few-shot prompting

Knowledge:
--- style-guide ---
Prefer val over var. Use data classes for DTOs.
--- examples ---
...
```

**`knowledgeTools()`** — returns knowledge entries as a list of callable tools. In agentic skills (`tools(...)`), this is wired automatically — no extra configuration needed. The LLM sees knowledge entries listed alongside action tools and fetches them on demand; content is never loaded unless called:

```kotlin
data class KnowledgeTool(
    val name: String,
    val description: String,   // LLM reads this to decide whether to call
    val call: () -> String,    // lazy — loads only when invoked
)
```

| Mode | When | How |
|------|------|-----|
| Eager (`toLlmContext()`) | Non-agentic skills | All knowledge content dumped into system prompt upfront |
| Lazy (`knowledgeTools()`) | Agentic skills — **automatic** | Knowledge listed as tools; content loaded only when the LLM calls them |

### Shared Knowledge

Knowledge lambdas close over shared state. Multiple agents can reference the same data source — no special API needed. Since knowledge is lazy, every call sees the latest state.

```kotlin
// Shared data source — both agents read from the same map
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

val recommender = agent<String, String>("recommender") {
    prompt("Recommend 1-2 products by ID and name. Follow the policies.")
    model { ollama("gpt-oss:120b-cloud"); temperature = 0.0 }
    skills { skill<String, String>("recommend", "Recommend products from catalog") {
        tools()
        knowledge("products", "Full product catalog with prices and stock") { catalog["products"]!! }
        knowledge("policies", "Recommendation rules") { catalog["policies"]!! }
    }}
}

val validator = agent<String, String>("validator") {
    prompt("Verify recommendations: products exist, are in stock, within budget. Reply VALID or INVALID: reason.")
    model { ollama("gpt-oss:120b-cloud"); temperature = 0.0 }
    skills { skill<String, String>("validate", "Validate recommendations against catalog") {
        tools()
        knowledge("products", "Full product catalog with prices and stock") { catalog["products"]!! }
        knowledge("policies", "Recommendation rules") { catalog["policies"]!! }
    }}
}

val pipeline = recommender then validator
pipeline("I want electronics, budget 100 dollars")
// recommender picks P04 — USB-C Hub ($35, in stock)
// validator confirms: "VALID"
```

Both agents load the same catalog on demand via knowledge tool calls. The recommender picks products; the validator cross-checks them against the same source of truth.

---
