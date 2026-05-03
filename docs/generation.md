[← Back to README](../README.md)

## Guided Generation

Agent inputs and outputs are data classes. `@Generable` + `@Guide` make them LLM-parseable — no manual schema authoring, no runtime boilerplate.

```kotlin
@Generable("Overall quality assessment of a code review")
data class ReviewResult(
    @Guide("Overall score from 0.0 to 1.0. Strict: < 0.6 means fail.")
    val score: Double,
    @Guide("One-sentence verdict: 'approved', 'needs revision', or 'rejected'.")
    val verdict: String,
    @Guide("Ordered list of specific issues found, or empty if approved.")
    val issues: List<String>,
)
```

**Three annotations:**

- **`@Generable("description")`** — marks the class as an LLM generation target. The optional description appears in auto-generated skill and type documentation.
- **`@Guide("description")`** — per-field (or per sealed variant) guidance for the LLM: range, format, constraints.
- **`@LlmDescription("...")`** — overrides the auto-generated `toLlmDescription()` verbatim for the rare case where the generated text doesn't fit.

Five artifacts are available at runtime via reflection:

| Artifact | API | Use |
|----------|-----|-----|
| LLM description | `ReviewResult::class.toLlmDescription()` | Convention-over-configuration markdown: class name, description, fields, types, `@Guide` texts |
| JSON Schema | `ReviewResult::class.jsonSchema()` | Constrained decoding (Ollama) or JSON mode (Anthropic) |
| Prompt fragment | `ReviewResult::class.promptFragment()` | Injected into system prompt automatically |
| Lenient deserializer | `fromLlmOutput<ReviewResult>(String)` | Parses partial/malformed LLM output; `null` on unrecoverable input |
| Streaming variant | `PartiallyGenerated<ReviewResult>` | Immutable accumulator; `withField()` returns a new copy as tokens arrive |

**`toLlmDescription()`** — auto-generated markdown, no extra work needed:

```markdown
## ReviewResult

Overall quality assessment of a code review

- **score** (Double): Overall score from 0.0 to 1.0. Strict: < 0.6 means fail.
- **verdict** (String): One-sentence verdict: 'approved', 'needs revision', or 'rejected'.
- **issues** (List<String>): Ordered list of specific issues found, or empty if approved.
```

When a skill's `IN`/`OUT` type carries `@Generable`, `Skill.toLlmDescription()` embeds the type shape inline — the LLM sees field names, types, and `@Guide` texts without any extra configuration.

**Two enforcement tiers — chosen at runtime based on the configured model:**

| Tier | Models | How |
|------|--------|-----|
| **Constrained** | Ollama, llama.cpp, vLLM | Grammar-constrained decoding — always valid JSON |
| **Guided** | Anthropic, OpenAI, Gemini | JSON mode + prompt fragment + lenient parse + fallback |

**Sealed types** — `@Guide` on each variant tells the LLM when to use it:

```kotlin
@Generable
sealed interface ReviewDecision {
    @Guide("Use when code passes all checks")
    data class Approved(val score: Double) : ReviewDecision

    @Guide("Use when code has fixable issues — list them in order of severity")
    data class NeedsRevision(
        @Guide("Specific issues, most critical first")
        val issues: List<String>
    ) : ReviewDecision

    @Guide("Use when fundamental design must change before any fix applies")
    data class Rejected(val reason: String) : ReviewDecision
}
```

The lenient deserializer routes to the correct subtype via the `"type"` discriminator. `.branch {}` receives exhaustively matched variants — no boilerplate.

**Streaming:**

```kotlin
val stream: Flow<PartiallyGenerated<ReviewResult>> = reviewer.stream(code)
stream.collect { partial ->
    partial.verdict?.let { showVerdict(it) }   // non-null = field has arrived
    partial.score?.let   { updateScore(it) }
}
```

---
