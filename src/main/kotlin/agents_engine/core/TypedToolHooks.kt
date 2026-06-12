package agents_engine.core

import agents_engine.generation.codec
import kotlin.reflect.KClass

/**
 * `agents_engine/core/TypedToolHooks.kt` — #4493 (PRD §typed hooks).
 * Reified observation hooks over the existing untyped listener slots:
 * consumers receive the tool's `@Generable` Args type instead of a
 * `Map<String, Any?>`, decoded through the same KSP-aware codec path the
 * runtime uses for typed tools.
 *
 * ```kotlin
 * agent.onToolCall<ChargeArgs>("charge_card") { args -> audit(args.amount) }
 * agent.onToolResult<ChargeArgs>("charge_card") { args, result -> reconcile(args, result) }
 * ```
 *
 * Semantics: observation only (never blocks or rewrites — that's the
 * `onBeforeToolCall` interceptor's job); fires only for [toolName]; a
 * payload that doesn't decode as [Args] is skipped silently (hooks must
 * not kill runs); chains with previously registered untyped listeners
 * rather than replacing them.
 */

/** Typed pre-execution observation of a tool call's arguments. */
inline fun <reified Args : Any> Agent<*, *>.onToolCall(
    toolName: String,
    crossinline block: (args: Args) -> Unit,
) {
    onBeforeToolCall { name, args ->
        if (name == toolName) {
            decodeToolArgs(Args::class, args)?.let(block)
        }
        Decision.Proceed
    }
}

/** Typed post-execution observation of a tool call's arguments and result. */
inline fun <reified Args : Any> Agent<*, *>.onToolResult(
    toolName: String,
    crossinline block: (args: Args, result: Any?) -> Unit,
) {
    val prior = toolUseListener
    onToolUse { name, args, result ->
        prior?.invoke(name, args, result)
        if (name == toolName) {
            decodeToolArgs(Args::class, args)?.let { typed -> block(typed, result) }
        }
    }
}

/** Decode an argument map into the typed shape; null (skip) when it doesn't fit. */
@PublishedApi
internal fun <T : Any> decodeToolArgs(klass: KClass<T>, args: Map<String, Any?>): T? =
    runCatching { klass.codec().decode(args) }.getOrNull()
