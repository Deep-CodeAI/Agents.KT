package agents_engine.generation

/**
 * `agents_engine/generation/ReflectionFallback.kt` — wraps reflection
 * bodies so consumers without `kotlin-reflect` on the classpath
 * gracefully degrade (return `null`) instead of crashing with
 * `NoClassDefFoundError` (#1705, #1718). Catches `LinkageError` and
 * `KotlinReflectionNotSupportedError`; lets other exceptions
 * propagate so real bugs aren't swallowed. See
 * `src/main/resources/internals-agent/generation/ReflectionFallback.md`
 * (#1837 / #1863).
 */

/**
 * Wraps reflection-using fallback bodies so consumers without `kotlin-reflect`
 * on their runtime classpath get graceful degradation (returns null) instead
 * of crashing with `NoClassDefFoundError` (#1705).
 *
 * With #1700–#1704 in, every `@Generable` runtime path has a KSP-generated
 * alternative. `kotlin-reflect` is now `compileOnly` in `agents-kt`'s POM —
 * consumers either:
 * 1. Apply `:agents-kt-ksp` (recommended), and the generated path covers
 *    all of `jsonSchema` / `toLlmDescription` / `constructFromMap`.
 * 2. Add `kotlin-reflect` to their own dependencies if they want the
 *    reflection fallback to remain available.
 * 3. Neither: reflection paths catch `NoClassDefFoundError` here and
 *    return null. Typed-tool deserialization routes through
 *    `onError.invalidArgs`; schema/description lookups return null and
 *    upstream code surfaces a clear "couldn't resolve" error.
 *
 * Two error families are caught:
 * - `LinkageError` (parent of `NoClassDefFoundError`) — fires when a
 *   reflective callsite references a kotlin-reflect class that is
 *   absent at runtime. Also covers `IncompatibleClassChangeError` etc.
 * - `KotlinReflectionNotSupportedError` (in `kotlin.jvm`, a sibling of
 *   `LinkageError` under `Error`) — fires when kotlin-stdlib itself
 *   detects that kotlin-reflect is missing and the caller invoked a
 *   member like `KClass::isSealed` that requires it. Specifically
 *   matters under the `agents-kt-no-reflect-test` subproject (#1718)
 *   which excludes kotlin-reflect from the runtime classpath.
 *
 * Non-Error exceptions propagate so real bugs aren't swallowed.
 */
internal object ReflectionFallback {

    inline fun <T> withReflection(body: () -> T): T? = try {
        body()
    } catch (_: LinkageError) {
        // NoClassDefFoundError, IncompatibleClassChangeError,
        // ClassFormatError — all signal "the classpath is incomplete for
        // this code path". Degrade gracefully.
        null
    } catch (_: kotlin.jvm.KotlinReflectionNotSupportedError) {
        // kotlin-stdlib's own "you called a reflect-requiring member
        // without kotlin-reflect on the classpath" signal. Same
        // degradation contract.
        null
    }
}
