# Agents.KT v0.4.5 — Reviewer-feedback patch

**Release date:** 2026-05-14

Patch release responding to v0.4.4 reviewer feedback. Two correctness fixes, one over-promise walked back honestly, CI / wrapper alignment, doc drift cleared.

```kotlin
implementation("ai.deep-code:agents-kt:0.4.5")
```

Drop-in for v0.4.4. Source-compatible; one behavior change documented below under **`wrap` race fix**.

---

## Fixed

### `wrap` is now race-safe under concurrent invocation

v0.4.4's `teacher wrap student` mutated `student.prompt` for the duration of one call and restored it in a `finally` block. Single-placement protected against multi-pipeline reuse, but not against:

- The same Pipeline launched from multiple coroutines (lanes race on the shared field — one lane's prompt could land in another's system message).
- A direct `student(input)` invocation racing with a wrap-pipeline mid-call.

The fix threads the effective prompt through `executeAgentic(agent, skill, input, effectivePrompt: String)` as a local parameter. `agent.prompt` is never mutated. New regression test `WrapConcurrencyTest` exercises 8 parallel lanes with distinguishable teacher outputs plus a direct invocation racing alongside, asserting zero cross-talk.

**Behavior change worth flagging.** Under the new design, the wrap override is visible to **agentic skills** (those that go through `executeAgentic`). `implementedBy` skills don't see it — and never reliably did. The old `GuessingGameTest` pattern that read `guesserAgent.prompt` from inside an `implementedBy` lambda worked only because of the mutation race; that test is rewritten in 0.4.5 to use an agentic stub `ModelClient` (the realistic shape of a wrapped student anyway). If your code relies on `implementedBy` seeing the wrap override, you need an agentic skill there instead.

### KSP `constructFromMap` no longer emits uncompilable nested references

v0.4.4 generated `Customer__GeneratedSchema.constructFromMap(it)` for every nested `@Generable` ref in a `data class`. If the nested class had default-valued primary-ctor params, the processor skipped emitting its `constructFromMap` (per the `canGenerate` rule), but the OUTER class's generated source still referenced that method → unresolved reference at compile time, not the runtime fallback the code comment claimed.

The fix routes nested refs through `<NestedClass>::class.constructFromMap(it)`. The `::class` receiver is a Kotlin compile-time class literal (no `kotlin-reflect` involvement at the call site), and the `@PublishedApi` extension's cache lookup handles both cases — generated companion present → fast path; absent → reflection fallback (or graceful null when consumers chose to keep `kotlin-reflect` off their classpath).

## Changed

### `kotlin-reflect` reverted to `implementation` (walking back v0.4.4's "reflect-free runtime" framing)

v0.4.4 moved `kotlin-reflect` to `compileOnly` and framed the release as "reflect-free runtime". That was over-stated. The KSP arc (#1701–#1704) does replace the high-frequency read paths on `@Generable` — `jsonSchema`, `toLlmDescription`, `constructFromMap` — but other hot paths still call `kotlin.reflect.full.*` regardless of KSP:

- `Skill.toLlmDescription` (skill auto-description)
- `AgenticLoop` system-message build
- `ToolDef` typed-tool `@Generable` validation
- `McpServer` runtime-discovered `@Generable` input detection
- `GenerableSupport.toLlmInput`
- `BranchBuilder.sealedSubclasses` (branch exhaustiveness check)

A consumer without `kotlin-reflect` on the classpath would hit `LinkageError` at agent construction, not just at LLM calls. The honest framing for 0.4.5:

- **KSP saves the per-call schema / description / construct reads** (still real, still worth applying `:agents-kt-ksp`).
- **Runtime still requires `kotlin-reflect`** for agent construction and a few adjacent paths.

A future PR will wrap each remaining `kotlin.reflect.full.*` callsite via `ReflectionFallback.withReflection { ... }` and ship a consumer-app smoke test that builds without `kotlin-reflect`. That work is too large to be a v0.4.5 patch.

### CI uses the wrapper's pinned Gradle

The `.github/workflows/ci.yml` job used to pin `gradle-version: '8.13'` via the setup-gradle action and call `gradle build` / `gradle test` directly. The wrapper says 9.5.0. The mismatch was a "passed locally, failed in CI" drift surface. Now: no `gradle-version` argument; `./gradlew build` / `./gradlew test`. Same Gradle everywhere.

### Doc drift cleared

- README's `main is prepared as 0.4.3` stale string updated.
- `wiki/API-Quick-Reference.md`'s `maxTurns` default corrected from `Int.MAX_VALUE` to the actual code default `8` (set in `BudgetConfig.kt`).

## Migration

From v0.4.4: drop-in, except for the one `wrap` behavior change — if you used `implementedBy` skills with a `lateinit var agent` capture to read the wrap-supplied prompt, switch to an agentic skill backed by a stub `ModelClient` (or a real one). Pattern in `GuessingGameTest` if you want a worked example.

From v0.4.2 or earlier: see the v0.4.4 release notes for the cumulative changes.

## Test count

975 root + 56 KSP = **1031 unit tests, 0 failures.**
