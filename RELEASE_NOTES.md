# Agents.KT v0.4.6 — `kotlin-reflect` actually optional

**Release date:** 2026-05-15

Follow-up to v0.4.5's open thread. v0.4.4 over-promised "reflect-free runtime"; v0.4.5 walked it back honestly; v0.4.6 finishes the job and pins the contract with a smoke-test subproject. Drop-in for v0.4.5.

```kotlin
implementation("ai.deep-code:agents-kt:0.4.6")
```

The premortem at `docs/premortem-0.4.6.md` defined the success criteria. This release ticks every box; the checklist comparison is at the bottom of these notes.

---

## What changed

### `kotlin-reflect` is `compileOnly` for real

Every remaining `kotlin.reflect.full.*` callsite is now either routed through the KSP-aware `hasGenerableAnnotation()` probe (cache-first, reflection-fallback) or wrapped via `ReflectionFallback.withReflection { ... }`. Specifically:

- `Skill.kt:208` — `generableDescription()` is wrapped; returns `""` when neither KSP companion nor `kotlin-reflect` is available.
- `ToolDef.kt:170` — typed-tool `require(... is @Generable)` uses `hasGenerableAnnotation()`. Error message now mentions the `:agents-kt-ksp` opt-in.
- `McpServer.kt:230` — runtime `@Generable` input detection uses `hasGenerableAnnotation()`.
- `GenerableSupport.kt:367/391` (`toLlmInput` branches) — uses `hasGenerableAnnotation()`. `generableToJson` body is wrapped (falls back to `toString()` when reflection is unavailable).
- `BranchBuilder.kt:75` — `sealedSubclasses` access wrapped. When `kotlin-reflect` is absent, the exhaustiveness check is skipped (consumer-side `when`-exhaustiveness is the belt; this framework check was the braces).
- `GenerableSupport.kt:457` — `fromLlmOutput`'s `isSealed` check wrapped. Sealed-root dispatch without `kotlin-reflect` is not supported in this slice (returns null cleanly); data-class dispatch routes through the unguarded `constructFromMap` path which hits the KSP cache first.

### `ReflectionFallback` now also catches `KotlinReflectionNotSupportedError`

`kotlin-stdlib` doesn't throw `NoClassDefFoundError` when reflect-required members are accessed without `kotlin-reflect` on the classpath — it throws its own `kotlin.jvm.KotlinReflectionNotSupportedError`, a sibling under `Error`. v0.4.6's `ReflectionFallback.withReflection` catches both error families. v0.4.5 only caught `LinkageError`; the new catch is what makes the no-reflect contract actually hold at runtime.

### `agents-kt-no-reflect-test` Gradle subproject (the proof)

A consumer-shaped subproject that excludes `kotlin-reflect` from its `compileClasspath`, `runtimeClasspath`, and the test counterparts. (The Kotlin compiler daemon's own classpath is left alone — the compiler internally uses `kotlin-reflect` to read its own argument metadata; stripping it there breaks the daemon.) The smoke test (`agents-kt-no-reflect-test/src/test/kotlin/smoke/NoReflectSmokeTest.kt`):

1. Asserts `Class.forName("kotlin.reflect.full.KClasses")` throws `ClassNotFoundException` — the load-bearing negative assertion. If the exclude leaks, the rest of the suite is meaningless and this guard fails first.
2. With a hand-written `__GeneratedSchema` companion (same shape `:agents-kt-ksp` emits), exercises `jsonSchema()`, `toLlmDescription()`, and `fromLlmOutput()` — proves the generated cache path covers the whole `@Generable` surface without touching `kotlin-reflect`.
3. With a `@Generable` class that has **no** generated companion, exercises the same three entry points — proves graceful degradation returns sane fallbacks (`{"type":"object","additionalProperties":false}` for schema, `## SimpleName` for description, `null` for `fromLlmOutput`) instead of crashing.

The subproject runs in the default `./gradlew test` aggregate. Failing it regresses the v0.4.6 contract.

## Consumer impact

- **You apply `:agents-kt-ksp`** (recommended): no change. The generated cache covers every `@Generable` call. `kotlin-reflect` is never needed.
- **You don't apply KSP, but you keep `kotlin-reflect` in your own runtime classpath**: no change. The wrapped reflection path runs exactly as it did pre-v0.4.6.
- **You don't apply KSP and you don't have `kotlin-reflect`**: the runtime no longer crashes at agent construction or first call. Behavior degrades cleanly — schema becomes the empty-object stub, LLM descriptions become `## SimpleName`, `fromLlmOutput` returns null, typed-tool dispatch routes through `onError.invalidArgs`. The agent runs; output quality may drop because the model sees less structural detail in the system prompt. Document this case to your users; `:agents-kt-ksp` is the right answer for production.

## Comparison against the premortem checklist

Each success criterion from `docs/premortem-0.4.6.md`:

- [x] **Published POM does NOT contain `kotlin-reflect`.** Build the local bundle; the generated `agents-kt-0.4.6.pom` has zero `kotlin-reflect` entries (`compileOnly` does not propagate to consumers).
- [x] **`agents-kt-no-reflect-test` builds and the smoke test passes.** Seven assertions, all green: the negative classpath probe plus six runtime-degradation paths.
- [x] **Main unit suite stays green.** 975 root tests + 56 KSP tests still pass with `kotlin-reflect` on the test classpath (`testImplementation`).
- [x] **Negative-path coverage.** The smoke test's `ReflectOnlyCustomer` (no generated companion) exercises the "reflect-absent + KSP-absent" graceful-degradation path that the premortem flagged as the most-likely silent-corruption risk.
- [x] **CHANGELOG and these notes name specific files / line numbers / contracts.** No floating claims; every "kotlin-reflect optional" sentence points at a wrapped callsite or the smoke test.

## What's NOT in this release

- Sealed-root `fromLlmOutput` dispatch without `kotlin-reflect` — needs a KSP-generated sealed dispatcher emitter. Returns null in the meantime (data classes are the common case).
- Generating skill auto-descriptions via KSP. Wrap was sufficient for v0.4.6; the cleaner refactor is a follow-up.
- The CI workflow update from v0.4.5 (stashed locally; needs a workflow-scoped token).
