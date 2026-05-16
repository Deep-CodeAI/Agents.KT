# `agents_engine/core/SkillRoute.kt` — LLM skill-routing decision

The structured output the framework asks the LLM to produce when an agent has multiple candidate skills and no manual `skillSelection { }` override (#641).

## Shape

```kotlin
@Generable("Skill routing decision")
data class SkillRoute(
    @Guide("Name of the chosen skill from the available list") val skillName: String,
    @Guide("0.0 to 1.0 — how confident the router is in this choice") val confidence: Double,
    @Guide("One short sentence explaining the choice")            val rationale: String,
)

class SkillRoutingException(message: String) : RuntimeException(message)
```

## Lifecycle

1. Agent has ≥2 skills whose `inType` matches the call's `input` and whose `outType` matches `OUT`. No manual `skillSelection { }` was set.
2. Framework builds a routing prompt listing each candidate skill's `name` + `toLlmDescription()`.
3. LLM returns a `SkillRoute` instance via the framework's `@Generable` structured-output path (`agents_engine.generation`).
4. `confidence` is compared to the agent's `skillSelectionConfidenceThreshold` (default `0.6`).
5. If `confidence >= threshold` → `skillName` is dispatched.
6. If `confidence < threshold` → `SkillRoutingException` is thrown.

## Observability

`rationale` is delivered to the optional `routerRationale { rationale -> ... }` listener on the agent. Useful for:
- Debugging why the router picked skill A over skill B.
- Surfacing the router's reasoning to a UI ("Routing: …").
- Logging for offline analysis of router quality.

## Confidence threshold

Default `0.6`. Tune via the agent builder:

```kotlin
agent<X, Y>("router") {
    skillSelectionConfidenceThreshold(0.8)   // stricter — fail more often
}
```

Higher threshold → more `SkillRoutingException`s, fewer wrong-skill dispatches. Lower → more dispatches, more chance of wrong-skill execution. Pick based on how costly a wrong dispatch is in your domain.

## `@Generable` / `@Guide`

These annotations come from `agents_engine.generation`. The framework's structured-output codepath uses them to:
- Embed `description` strings into the LLM prompt (so the LLM knows what each field means).
- Build the JSON schema for tool-use-style structured output.
- Drive the reflective deserializer that parses the LLM's response back into the typed `SkillRoute` instance.

See `internals-agent/generation/` adjuncts for the wider picture.

## Related files

- `Agent.kt` — the dispatcher that invokes routing when needed.
- `AgenticLoop.kt` — does NOT run for the routing call (routing is a single structured-output call, not multi-turn).
- `generation/Generable.kt`, `generation/Guide.kt` — the annotations.
- `model/SelectSkillByLlm.kt` (if present) — the routing helper that calls the LLM.
