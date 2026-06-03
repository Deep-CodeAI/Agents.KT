package agents_engine.generation

import kotlin.reflect.KClass

/**
 * `agents_engine/generation/GenerableCodec.kt` (#2803) — a typed decoder for a single `@Generable`
 * type: a JSON-ish field map → a [T] instance (or null when the shape doesn't match).
 *
 * Resolved once per type via [KClass.codec] (in `GenerableSupport.kt`); the two implementations are
 * the KSP-generated `constructFromMap` and the reflective fallback. This is the *one boundary* where
 * the weakly-typed wire shape (`Map<*, *>` / `KClass<*>`) becomes typed — the unchecked casts that
 * used to litter `constructFromMap` / `constructFromMapReflective` / `coerceValue` / `fromLlmOutput`
 * collapse to the single cast inside `codec()`.
 */
internal fun interface GenerableCodec<out T : Any> {
    fun decode(fields: Map<*, *>): T?
}
