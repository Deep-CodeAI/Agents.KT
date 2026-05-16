# `agents_engine/generation/Annotations.kt` — `@Generable`, `@LlmDescription`, `@Guide`

The three annotations that turn ordinary Kotlin types into LLM-generable structured outputs.

## `@Generable(description: String = "")`

```kotlin
@Generable("A user's order with line items")
data class Order(val id: String, val items: List<LineItem>)
```

Marks a `data class` or `sealed interface` as an LLM generation target. The framework's runtime helpers (`jsonSchema`, `toLlmDescription`, `fromLlmOutput`) and the KSP processor look for this annotation to decide which classes to generate metadata for.

- `description` is an optional top-level docstring shown to the LLM. If `@LlmDescription` is also present, this field is ignored.
- Targets: `CLASS`. Retention: `RUNTIME` (the framework reads it via reflection AND KSP).

## `@LlmDescription(text: String)`

```kotlin
@Generable
@LlmDescription("""
    A user-submitted feedback record.
    Always set sentiment to one of: POSITIVE, NEUTRAL, NEGATIVE.
""".trimIndent())
data class Feedback(val text: String, val sentiment: String)
```

Overrides the auto-generated description for a `@Generable` class. When present, `text` is returned verbatim from `toLlmDescription()` — the framework does not synthesize anything from constructor params or `@Guide` annotations.

Use when you need full control over the LLM's view of the type (multi-paragraph instructions, examples, enumerated allowed values).

## `@Guide(description: String)`

```kotlin
@Generable("Skill routing decision")
data class SkillRoute(
    @Guide("Name of the chosen skill from the available list") val skillName: String,
    @Guide("0.0 to 1.0 — how confident the router is in this choice") val confidence: Double,
    @Guide("One short sentence explaining the choice") val rationale: String,
)
```

Per-field guidance for the LLM. The auto-description path concatenates each field's name, type, and `@Guide` text into the prompt fragment.

Two target shapes:
- **Constructor parameter** — tells the LLM what to put in this field (range, format, constraints).
- **Sealed subclass** — tells the LLM when to choose this variant:

```kotlin
@Generable("A user action")
sealed interface UserAction {
    @Guide("Choose when the user wants to cancel and undo.")
    data object Cancel : UserAction
    @Guide("Choose when the user wants to confirm and proceed.")
    data class Confirm(val token: String) : UserAction
}
```

## How the framework reads these

- **Reflection path** (`GenerableSupport.kt`) — `findAnnotation<Generable>()` / `findAnnotation<Guide>()` walks the `KClass` and constructor params.
- **KSP path** (`agents-kt-ksp`) — the processor reads these annotations at compile time, emits `<ClassName>__GeneratedSchema.kt` with `JSON_SCHEMA` / `LLM_DESCRIPTION` constants byte-identical to what reflection would produce.

Both paths are honored: KSP wins when present (no runtime reflection), reflection takes over when KSP is absent.

## Related files

- `GenerableSupport.kt` — the runtime helpers that consume these annotations.
- `ReflectionFallback.kt` — graceful degradation when reflection is unavailable.
- `:agents-kt-ksp` (sibling module) — compile-time codegen reading these annotations.
- `SkillRoute.kt` — the canonical example of `@Generable` + `@Guide` use.
