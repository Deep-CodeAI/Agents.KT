package agents_engine.generation

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
 * `LinkageError` (parent of `NoClassDefFoundError`) is also caught — JDK
 * occasionally throws `IncompatibleClassChangeError` etc. in adjacent
 * scenarios; better to degrade than crash.
 *
 * Non-LinkageError exceptions propagate so real bugs aren't swallowed.
 */
internal object ReflectionFallback {

    inline fun <T> withReflection(body: () -> T): T? = try {
        body()
    } catch (_: LinkageError) {
        // NoClassDefFoundError, IncompatibleClassChangeError,
        // ClassFormatError — all signal "the classpath is incomplete for
        // this code path". Degrade gracefully.
        null
    }
}
