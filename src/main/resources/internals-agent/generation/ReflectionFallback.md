# `agents_engine/generation/ReflectionFallback.kt` — graceful degradation when reflection is absent

A tiny utility object that wraps reflection-using code so consumers without `kotlin-reflect` on the runtime classpath get `null` instead of a crash.

## API

```kotlin
internal object ReflectionFallback {
    inline fun <T> withReflection(body: () -> T): T?
}
```

Returns `body()` on success. Catches two error families and returns `null` on either; other exceptions propagate.

## Caught errors

| Error | Why |
|---|---|
| `LinkageError` (and subtypes: `NoClassDefFoundError`, `IncompatibleClassChangeError`, `ClassFormatError`) | "The classpath is incomplete for this code path." Fires when a reflective callsite references a `kotlin-reflect` class that's absent at runtime. |
| `kotlin.jvm.KotlinReflectionNotSupportedError` | kotlin-stdlib's own signal that the caller invoked a member like `KClass::isSealed` that requires `kotlin-reflect`. Important in the `agents-kt-no-reflect-test` subproject (#1718) which excludes `kotlin-reflect` from the runtime classpath to verify graceful degradation. |

## What it does NOT catch

Plain `Exception`s — those signal real bugs (a `NullPointerException` in user reflection logic, an `IllegalAccessException` on a private field) and should propagate so the user sees them.

The choice to catch ONLY `Error` subtypes is deliberate: `Error`s mean "the environment can't run this code"; `Exception`s mean "the code is wrong."

## Where it's used

Every reflection-using line in `GenerableSupport.kt` is wrapped:

```kotlin
val params = ReflectionFallback.withReflection {
    primaryConstructor?.parameters?.mapNotNull { ... }
} ?: emptyList()

val annotation = ReflectionFallback.withReflection {
    findAnnotation<Generable>()
}
```

Also used by `Skill.toLlmDescription()` to derive `@Generable` constructor structure from `inType` / `outType`.

## The broader story (#1705, #1718)

With KSP-generated metadata (#1701-#1704), every `@Generable` runtime path has a generated alternative. `kotlin-reflect` is `compileOnly` in `agents-kt`'s POM. Consumers either:

1. **Apply `:agents-kt-ksp` (recommended)** — generated path covers `jsonSchema` / `toLlmDescription` / `constructFromMap`. No reflection needed.
2. **Add `kotlin-reflect` to their own dependencies** — reflection fallback works.
3. **Neither** — reflection paths return `null`; upstream code surfaces clear "couldn't resolve" errors via `onError.invalidArgs` etc.

This file is what makes (3) safe instead of crashy.

## Related files

- `GenerableSupport.kt` — the primary user, wrapping every reflection call.
- `Skill.kt` — uses this in `generableDescription()` for auto-description.
- `:agents-kt-no-reflect-test` (sibling test module) — verifies the graceful-degradation contract.
