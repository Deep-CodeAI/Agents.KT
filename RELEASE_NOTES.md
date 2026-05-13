# Agents.KT v0.4.4 — KSP, `wrap`, and reflect-free runtime

**Release date:** 2026-05-13

First Maven Central release after **v0.4.2**. The internal `v0.4.3` tag existed on GitHub but never reached Maven Central — its content (BC pin completeness across both Gradle modules) is folded into 0.4.4 alongside the KSP arc, the `wrap` operator, and a reflect-free runtime.

```kotlin
implementation("ai.deep-code:agents-kt:0.4.4")
```

Same artifact coordinates as before. Drop-in for 0.4.2 — every new public API has defaults; existing 0.4.x code compiles unchanged.

---

## What's new since v0.4.2

### The KSP arc — `@Generable` is now compile-time

A six-step progression replaces every reflection walk for `@Generable` types with KSP-generated companion objects. Apply `:agents-kt-ksp` in your build and:

```kotlin
// build.gradle.kts
plugins {
    id("com.google.devtools.ksp") version "2.3.7-1.x"
}
dependencies {
    implementation("ai.deep-code:agents-kt:0.4.4")
    ksp("ai.deep-code:agents-kt-ksp:0.4.4")
}
```

What lights up for every `@Generable` class in your code:

| What runs today | What ships in 0.4.4 with KSP applied |
|---|---|
| `KClass.jsonSchema()` walks the class reflectively on every call | Reads a `const val JSON_SCHEMA: String` baked at compile time. Byte-identical output. (#1701, #1702) |
| `KClass.toLlmDescription()` walks + checks `@Guide` annotations | Reads a `const val LLM_DESCRIPTION: String`. `@LlmDescription(text)` override is baked into the constant. (#1703) |
| `KClass.constructFromMap(map)` reflects to find the primary ctor and invokes via `callBy` | Calls a `@JvmStatic constructFromMap(Map<*,Any?>): T?` with named-arg construction. Strict overflow / type rejection preserved (#665, #855). (#1704) |
| Sealed roots reflect through `sealedSubclasses` | Generated `oneOf` schema with `"type"` discriminators; generated dispatch by type-name to each variant's `constructFromMap`. (#1702, #1704) |

**Compile-time error coverage.** The processor also catches `@Generable` misuses at build time — non-sealed interfaces, annotation classes, enums, abstract classes, classes without a parameterised primary constructor, and fields with types outside the supported set (`String`, `Int`, `Long`, `Double`, `Float`, `Boolean`, `List<T>`, nested `@Generable`). What used to be a silent `{"type":"string"}` fallback at runtime is now a red squiggle in the IDE (#1700, #1701).

**`kotlin-reflect` is no longer on the runtime classpath.** Moved from `implementation` to `compileOnly` in the published POM. Consumers see a ~3.5 MB lighter dependency tree. Reflection-using fallback paths still work for consumers who add `kotlin-reflect` themselves; for everyone else, the `ReflectionFallback.withReflection { ... }` wrap catches `LinkageError` and degrades gracefully — typed-tool deserialization returns null and routes through `onError.invalidArgs` instead of crashing (#1705).

**Defensive emission gate.** Sealed `@Generable` parents whose variants aren't visible to KSP (incremental-compile race, edge cases) skip schema emission; reflection takes over at runtime against the full JVM hierarchy. Single safety net for the case where the codegen view of the world is incomplete (#1705).

### The `wrap` operator — teacher / student prompt override

`teacher wrap student` is a new composition operator: the teacher agent's String output becomes the student's system prompt for one call only. The student's baked-in prompt is restored after.

```kotlin
val teacher = agent<String, String>("teacher") {
    skills { skill<String, String>("brief", "Produce a prompt") {
        implementedBy { topic -> "You are a $topic specialist. Answer concisely." }
    } }
}
val worker = agent<String, String>("worker") {
    prompt("baked-in default")
    model { claude("claude-haiku-4-5-20251001"); apiKey = key }
    skills { skill<String, String>("answer", "Answer using the prompt") { tools() } }
}

val pipeline = teacher wrap worker
val result = pipeline("Kotlin coroutines")
// teacher emits "You are a Kotlin coroutines specialist. Answer concisely."
// worker invokes Claude with that as its system prompt
```

Two framings the test suite proves out:

- **Education** — one generalist student is reused across many narrow jobs because the teacher hands it task-specific context. Headline test: the teacher tells the student to compute Fibonacci via a `fib` tool; same student, different prompts, different jobs (#1698).
- **Security** — the teacher locks down the student's task surface for the call. The student can't drift to its default prompt. Demonstrated by the guessing-game test (#1699): an oracle agent narrows the search window after each round; the guesser agent reads the teacher-supplied prompt and binary-searches to find the secret.

Live cross-provider proof in the integration suite: Claude as the teacher emits the prompt, Ollama as the student computes `fib(10)` via its registered tool (#1698 live test).

`wrap` returns a `Pipeline<IN, OUT>`, so it composes with `then` / `Pipeline.loop {}` / the rest. Closes the last open Phase 1 PRD item.

### BouncyCastle pin completeness

Internal tag `v0.4.3` existed on GitHub to complete the BC 1.84 pin in the `:agents-kt-ksp` subproject too, plus prune stale 1.80 entries from `gradle/verification-metadata.xml`. That work is folded into 0.4.4 (#1695, never reached Maven Central). The four dependabot advisories on `main` (BCprov / BCpg / BCpkix / BCutil 1.80) should clear after the next scanner pass.

### Other

- Gradle wrapper 9.4.1 → 9.5.0 (was prepped during the BC pass).
- `kotlinx-coroutines-core` and `kotlinx-coroutines-test` 1.10.2 → 1.11.0.
- Live integration tests for the wrap operator across Claude (teacher) + Ollama (student) (#1698 live test, #1699 live test).

---

## Migration

### From v0.4.2 (the previous published release)

Drop-in for the existing API surface. Two consumer-side changes to be aware of:

1. **`kotlin-reflect` is no longer on the runtime classpath.** If you don't apply `:agents-kt-ksp` AND you use `@Generable` types, the reflection paths degrade to null returns + placeholder schemas. Recommended fix: apply `:agents-kt-ksp` (covers every `@Generable` hot path). Alternative: add `kotlin-reflect` to your own dependency declarations.

2. **`wrap` is new infix syntax.** Code that uses `wrap` as a function name on its own types won't collide unless it's also `infix`-on-Agent-targeted; conflict is unlikely. Aliasing `import agents_engine.composition.wrap.wrap as wrapInto` if needed.

### From v0.3.x or earlier

Same as v0.4.2 migration (three model providers, fail-fast precheck, masked `apiKey` toString) — see the v0.4.2 release notes for the full intermediate-jump story — plus the v0.4.4 changes above.

---

## What's next

Open PRD items after this release:

- **Constrained-decoding integration** — wire the already-generated `JSON_SCHEMA` constants into Ollama's structured-output mode, Anthropic's `tool` JSON-mode, and OpenAI's `response_format` JSON-mode. Provider-side enforcement instead of the prompt-fragment approach.
- **`Tool<IN, OUT>` hierarchy + `McpTool<IN, OUT>`** — unlocks typed `grants { tools(writeFile, mcpServer.fooTool) }` permissions.
- **Structure-level budgets** — `budget { }` on Pipeline / Forum / Parallel / Loop.
- **Streaming (`Flow<LlmResponseChunk>`)** — the dead-air-spinner killer; touches `ModelClient` and all three adapters.
- **Native CLI binary (GraalVM)** — KSP-generated code is now AOT-friendly, so this is unblocked.
- **Gemini adapter** — finishes the multi-provider line.

Full breakdown in [`docs/roadmap.md`](docs/roadmap.md).
