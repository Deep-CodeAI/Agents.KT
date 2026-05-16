# `agents_engine/model/ModelConfig.kt` — the `model { }` slot

The DSL slot every agent must fill (or supply a `client` directly) to talk to an LLM.

## Shape

```kotlin
enum class ModelProvider { OLLAMA, ANTHROPIC, OPENAI }

data class ModelConfig(
    val name: String,
    val provider: ModelProvider,
    val temperature: Double = 0.7,
    val host: String = "localhost",         // Ollama only
    val port: Int = 11434,                   // Ollama only
    val client: ModelClient? = null,         // override the auto-built client
    val apiKey: String? = null,              // required for Anthropic / OpenAI
    val anthropicBaseUrl: String = "https://api.anthropic.com",
    val openAiBaseUrl: String = "https://api.openai.com",
    val maxTokens: Int = 4096,
)
```

## DSL

```kotlin
agent<X, Y>("...") {
    model {
        ollama("gpt-oss:120b-cloud")
        // or: claude("claude-opus-4-7-20250514"); apiKey = System.getenv("ANTHROPIC_API_KEY")
        // or: openai("gpt-4o-mini"); apiKey = System.getenv("OPENAI_API_KEY")
        temperature = 0.3
        maxTokens = 8192
    }
}
```

The builder's three factory calls (`ollama`, `claude`, `openai`) set both `name` and `provider`. The Anthropic / OpenAI paths require `apiKey` — `build()` fails with a precise error message naming the call shape (e.g. `model { claude("...") } requires apiKey to be set`).

## Lazy client construction

`client` is null in the typical case. The `AgenticLoop` constructs the appropriate `ModelClient` lazily so the agent's full tool catalog is available at construction time (the client needs the tool list for schema generation). Override `client` only for tests or custom adapters.

## API-key masking

`toString()` is overridden to mask `apiKey`:
- `null` → `"null"`
- `≤ 8 chars` → `"***"`
- otherwise → `"sk-abc…45chars"` — prefix + suffix length, never the full key.

`equals` / `hashCode` still include `apiKey` (needed for cache keying), but observation surfaces (loggers, stack traces, future serialization) never see the raw value.

## Related files

- `ModelClient.kt` — the interface `client` implements when set.
- `OllamaClient.kt`, `ClaudeClient.kt`, `OpenAiClient.kt` — the shipped adapters constructed lazily.
- `Agent.kt` — the `model { }` builder slot.
