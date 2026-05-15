# Premortem — Agents.KT v0.4.6

**Status:** Draft. **Owner:** kskobeltsyn. **Date drafted:** 2026-05-15. **Target release:** 0.4.6.

This is a **premortem release note**, not a postmortem: written before any code changes for v0.4.6 ship, naming what we're trying to accomplish, what could fail, and the success criteria we'll measure against. The actual `RELEASE_NOTES.md` will be written when the work lands — and we'll compare to this document for honesty.

---

## Goal

**Make `kotlin-reflect` actually optional at runtime, for real this time.**

v0.4.4 over-promised "reflect-free runtime"; v0.4.5 walked it back honestly. v0.4.6 finishes the job by wrapping every remaining `kotlin.reflect.full.*` callsite so a consumer without `kotlin-reflect` on their runtime classpath either gets the KSP-generated fast path or graceful degradation, and **proves it** with a separate Gradle subproject whose classpath excludes `kotlin-reflect`.

If we cannot demonstrate the proof, we don't claim the win.

## Scope

In:
- Wrap every direct `findAnnotation<>`, `primaryConstructor`, `sealedSubclasses`, `callBy`, `hasAnnotation`, `memberProperties`, `allSuperclasses` call in `src/main/`.
- Move `kotlin-reflect` from `implementation` back to `compileOnly` in `agents-kt`'s `build.gradle.kts`.
- Add `agents-kt-no-reflect-test/` Gradle subproject — a minimal consumer-shaped app whose dependency graph DOES NOT contain `kotlin-reflect`. Subproject builds + runs a small smoke test exercising the framework's main surface (agent construction, agentic loop with a stub `ModelClient`, typed-tool dispatch). Build is part of `./gradlew check`.
- Update `CHANGELOG.md` and `RELEASE_NOTES.md` honestly with what works and what degrades.

Out:
- Generating skill auto-descriptions via KSP (would be cleaner than wrapping, but it's another full pass; deferred).
- KSP-side processing of MCP-discovered types (impossible by definition — MCP types arrive at runtime).
- Switching to bytecode-level annotation reading (gross, brittle, not worth it for one annotation).

## Inventory — every reflection callsite to address

Audited 2026-05-15. The non-wrapped sites still using `kotlin.reflect.full.*`:

| File:line | Function | What it does | Fix path |
|---|---|---|---|
| `Skill.kt:207-222` | `KClass.generableDescription()` | Per-skill auto-description, walks `findAnnotation<Generable>`, `primaryConstructor.parameters`, per-param `findAnnotation<Guide>`. Called by `Skill.toLlmDescription`. | Wrap in `ReflectionFallback.withReflection`; returns `""` if reflection unavailable. Skill description is degraded but agent still works. |
| `Skill.kt:152-153` | `Skill.toLlmDescription` itself | Calls `inType.generableDescription()` and `outType.generableDescription()`. | Inherits the wrap via #207. No direct change needed. |
| `ToolDef.kt:170` | typed-tool builder | `require(argsClass.findAnnotation<Generable>() != null)`. Hard-fails at agent build if args class isn't `@Generable`. | Use new helper `hasGenerableAnnotation()` that checks the KSP-generated cache first (true reflect-free for KSP-applied consumers), then wraps `findAnnotation` for the reflection fallback. The check returns `false` cleanly when reflect-unavailable AND no generated companion exists — and we then degrade the `require()` to a warning at build time (rare edge case; most consumers apply KSP). |
| `McpServer.kt:230` | runtime MCP type detection | `inType.findAnnotation<Generable>() != null` to pick the ExposedSkill branch. | Use the same `hasGenerableAnnotation()` helper. |
| `GenerableSupport.kt:367/391` | `toLlmInput` branch | Checks `cls.findAnnotation<Generable>() != null` to decide JSON serialization vs `toString()`. Runs per turn in the agentic loop. | Same helper. Degrades to `toString()` when both KSP-absent and reflect-absent — same fallback we already have for non-@Generable types. |
| `BranchBuilder.kt:75` | branch exhaustiveness check | `sourceOutType.sealedSubclasses` to verify every variant has a case. Fires at agent build for sealed-typed branches. | Wrap. When reflect-unavailable, skip the exhaustiveness check; rely on compile-time `when`-exhaustiveness from the consumer's side. (Branch users almost always also have a typed `when` block — the framework's check is belt-and-braces.) |

GenerableSupport's other reflection sites (lines 139, 160, 166, 169, 272, 295, 310, 403-415, 491, 514, 542) are already inside reflection-fallback function bodies wrapped by `ReflectionFallback.withReflection` at the entry points (`KClass.jsonSchema`, `toLlmDescription`, `constructFromMap`, `fromLlmOutput`). No new wraps needed there.

## Risks and what could go wrong

### High-severity risks

1. **The smoke-test subproject doesn't actually exclude `kotlin-reflect`.** Gradle resolution is sneaky; `kotlin-reflect` might land transitively via `kotlin-test`, `kotlinx-coroutines`, or even `kotlin-stdlib-jdk8` in older variants. **Mitigation:** assert at smoke-test runtime via `Class.forName("kotlin.reflect.full.KClasses")` — if it resolves, the test fails with "kotlin-reflect leaked into the smoke-test classpath." This is the load-bearing assertion of the whole release.

2. **A wrap is forgotten.** Easy to miss one — KSP didn't touch every site, and I'm doing the audit by grep. **Mitigation:** the smoke-test runs the same scenarios our integration tests cover (agent construction, typed-tool dispatch, branch, MCP exposure, agentic loop). If any of those hit an unwrapped path, the smoke test crashes with `LinkageError`. Better than a consumer finding it in production.

3. **A wrapped fallback returns a value that's wrong-but-doesn't-crash.** Worse than a crash because it silently produces bad behavior. Example: `hasGenerableAnnotation()` returns false for a class that IS `@Generable` but the consumer didn't apply KSP — `toLlmInput` falls back to `toString()`, model sees a JVM `Object@deadbeef` instead of structured JSON. **Mitigation:** the smoke-test consumer DOES apply KSP, so all `@Generable` types are detected. The "reflect-absent + KSP-absent" combo is documented as "consumer must apply at least one" — explicit error rather than silent corruption.

### Medium-severity risks

4. **CHANGELOG / RELEASE_NOTES drift back into over-promising.** Same trap as v0.4.4. **Mitigation:** finishing the release means *first* the smoke-test passes, *then* I write the release notes from facts. The premortem (this doc) lists the success criteria; if I can't tick all boxes, the release ships as a partial (and the notes say so).

5. **A degraded behavior hits an existing test.** Some unit test exercises a path that now degrades. **Mitigation:** the regular test suite still runs with `kotlin-reflect` on the test classpath (via `testImplementation`). Only the new smoke-test runs without. The reflection paths still work for everyone who has the dep.

6. **The `require()` in ToolDef.kt downgrade from hard-fail to silent-skip changes consumer behavior.** Old: typo'd Args class without `@Generable` blew up loudly. New (without KSP and reflect): silent skip, model sees Map<String,Any?> generic schema. **Mitigation:** keep `require()` strict when EITHER KSP cache OR reflection can answer; only degrade if both unavailable. Most consumers have at least one.

### Low-severity risks

7. **Verification-metadata churn.** Adding a new subproject pulls dependencies (kotlinx-test maybe?). **Mitigation:** regenerate after the subproject is set up; commit.

8. **CI takes longer because the new subproject adds compile time.** Minor.

## Success criteria

For v0.4.6 to ship with the "kotlin-reflect optional" claim, ALL of these must be true:

- [ ] **Published POM does NOT contain `kotlin-reflect`.** Same check as v0.4.4 attempted; `grep -c kotlin-reflect ~/.m2/repository/.../agents-kt-0.4.6.pom == 0`.
- [ ] **`agents-kt-no-reflect-test` subproject builds AND its smoke test passes.** The test:
  - Constructs an agent with one `@Generable` data class (`@Generable data class Foo(val x: String, val y: Int)`).
  - Calls the agent via a stub `ModelClient`.
  - Verifies typed-tool dispatch works through the KSP-generated `constructFromMap`.
  - Asserts `kotlin.reflect.full.KClasses` is NOT loadable via `Class.forName` — confirms reflection lib isn't on the classpath.
  - Failure of the assertion fails CI.
- [ ] **Main unit suite stays green** (kotlin-reflect on the classpath; existing behavior preserved).
- [ ] **At least one negative-path test:** an agent with an `@Generable` class WITHOUT KSP applied, with `kotlin-reflect` on the classpath — verifies reflection fallback still works (that's our migration story for consumers who haven't adopted KSP).
- [ ] **CHANGELOG and RELEASE_NOTES name specific files / line numbers / contracts.** No "reflect-free runtime" without proof; the smoke test IS the proof.

## What this premortem does NOT cover

- Performance benchmarking (the JAR-size + cold-start wins are documented in #1701-#1704 release notes; v0.4.6 doesn't change those).
- v0.5.0 or a major bump — staying in 0.4.x; this is a contract-strengthening patch.
- The deferred CI workflow update from v0.4.5 (stashed locally; needs a workflow-scoped token; not part of v0.4.6 mainline work).

## Comparison checklist for the post-release notes

When the actual `RELEASE_NOTES.md` for 0.4.6 is written, every "kotlin-reflect optional" claim must point at one of:

1. The smoke-test subproject's name (concrete proof).
2. A specific wrapped callsite (concrete change).
3. A specific success-criterion box from this document (concrete acceptance gate).

No floating claims. If the smoke test failed and we're shipping anyway, the release notes must say which assertion is currently red and why.

---

*Written before the work; will be compared against the actual outcome before the release ships.*
